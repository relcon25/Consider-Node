package edu.harvard.iq.datatags.parser.decisiongraph;

import edu.harvard.iq.datatags.model.graphs.DecisionGraph;
import edu.harvard.iq.datatags.model.graphs.nodes.AskNode;
import edu.harvard.iq.datatags.model.graphs.nodes.CallNode;
import edu.harvard.iq.datatags.model.graphs.nodes.EndNode;
import edu.harvard.iq.datatags.model.graphs.nodes.Node;
import edu.harvard.iq.datatags.model.graphs.nodes.RejectNode;
import edu.harvard.iq.datatags.model.graphs.nodes.SetNode;
import edu.harvard.iq.datatags.model.graphs.nodes.TodoNode;
import edu.harvard.iq.datatags.model.types.AggregateType;
import edu.harvard.iq.datatags.model.types.AtomicType;
import edu.harvard.iq.datatags.model.types.CompoundType;
import edu.harvard.iq.datatags.model.types.TagType;
import edu.harvard.iq.datatags.model.types.ToDoType;
import edu.harvard.iq.datatags.model.values.AggregateValue;
import edu.harvard.iq.datatags.model.graphs.Answer;
import edu.harvard.iq.datatags.model.graphs.ConsiderAnswer;
import edu.harvard.iq.datatags.model.graphs.nodes.ConsiderNode;
import edu.harvard.iq.datatags.model.values.AtomicValue;
import edu.harvard.iq.datatags.model.values.CompoundValue;
import edu.harvard.iq.datatags.parser.decisiongraph.ast.AstAskNode;
import edu.harvard.iq.datatags.parser.decisiongraph.ast.AstCallNode;
import edu.harvard.iq.datatags.parser.decisiongraph.ast.AstConsiderAnswerSubNode;
import edu.harvard.iq.datatags.parser.decisiongraph.ast.AstConsiderNode;
import edu.harvard.iq.datatags.parser.decisiongraph.ast.AstEndNode;
import edu.harvard.iq.datatags.parser.decisiongraph.ast.AstNode;
import edu.harvard.iq.datatags.parser.decisiongraph.ast.AstRejectNode;
import edu.harvard.iq.datatags.parser.decisiongraph.ast.AstSetNode;
import edu.harvard.iq.datatags.parser.decisiongraph.ast.AstTodoNode;
import edu.harvard.iq.datatags.parser.exceptions.BadSetInstructionException;
import edu.harvard.iq.datatags.parser.exceptions.DataTagsParseException;
import edu.harvard.iq.datatags.runtime.exceptions.DataTagsRuntimeException;
import static edu.harvard.iq.datatags.util.CollectionHelper.C;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The result of parsing a decision graph code. Can create an actual decision
 * graph, when provided with a tag space (i.e a @{link CompoundType} instance).
 * Also provides access to the AST, via {@link #getNodes()}.
 *
 * @author michael
 */
public class DecisionGraphParseResult {

    private final List<? extends AstNode> astNodes;

    /**
     * Maps a name of a slot to its fully qualified version (i.e from the top
     * type). For fully qualified names this is an identity function.
     */
    final Map<List<String>, List<String>> fullyQualifiedSlotName = new HashMap<>();

    private CompoundType topLevelType;

    private DecisionGraph product;

    private final AstNodeIdProvider nodeIdProvider = new AstNodeIdProvider();

    private URI source;

    public DecisionGraphParseResult(List<? extends AstNode> someAstNodes) {
        astNodes = someAstNodes;
    }

    /**
     * Creates a ready-to-run {@link DecisionGraph} from the parsed nodes and
     * the tagspace.
     *
     * @param tagSpace The tag space used in the graph.
     * @return A ready-to-run graph.
     * @throws DataTagsParseException
     */
    public DecisionGraph compile(CompoundType tagSpace) throws DataTagsParseException {
        buildTypeIndex(tagSpace);

        product = new DecisionGraph();

        // stage 1: Ensure all AST nodes have ids.
        addIds(astNodes);

        // stage 2: Break nodes to componsnets, and
        // stage 3: Compile and link direct nodes.
        EndNode endAll = new EndNode("[SYN-END]");
        try {
            breakAstNodeList(astNodes).forEach((segment)
                    -> buildNodes(segment, endAll));
        } catch (RuntimeException re) {
            Throwable cause = re.getCause();
            if ((cause != null) && (cause instanceof DataTagsParseException)) {
                DataTagsParseException pe = (DataTagsParseException) cause;
                if (pe.getOffendingNode() == null) {
                    pe.setOffendingNode(C.head(astNodes));
                }
                throw pe;
            } else {
                throw re;
            }
        }

        product.setStart(product.getNode(C.head(astNodes).getId()));
        product.setTopLevelType(topLevelType);
        product.setSource(source);
        if (source != null) {
            product.setTitle(C.last(source.getPath().split("/")));
        }

        // TODO Graph-level validators may go here.
        return product;
    }

    /**
     * Break a list of {@link AstNode}s to list of semantically connected nodes.
     * The list generated by the AST parser contains connections that do not
     * make semantic sense - such as a terminating node's next pointer. We break
     * the list on those. This is needed only on the top-level list of nodes -
     * the recursion will take care to connect the nodes that syntactically come
     * after terminating nodes in the answer sub-charts.
     *
     * @param parsed
     * @return
     */
    List<List<AstNode>> breakAstNodeList(List<? extends AstNode> parsed) {
        List<List<AstNode>> res = new LinkedList<>();
        List<AstNode> cur = new LinkedList<>();
        AstNode.Visitor<Boolean> chainBreaker = new AstNode.Visitor<Boolean>() {

            @Override
            public Boolean visit(AstConsiderNode astNode) {
                return false;
            }

            @Override
            public Boolean visit(AstAskNode astNode) {
                return false;
            }

            @Override
            public Boolean visit(AstCallNode astNode) {
                return false;
            }

            @Override
            public Boolean visit(AstEndNode astNode) {
                return true;
            }

            @Override
            public Boolean visit(AstSetNode astNode) {
                return false;
            }

            @Override
            public Boolean visit(AstRejectNode astNode) {
                return true;
            }

            @Override
            public Boolean visit(AstTodoNode astNode) {
                return false;
            }
        };

        for (AstNode node : parsed) {
            cur.add(node);
            if (node.accept(chainBreaker)) {
                res.add(cur);
                cur = new LinkedList<>();
            }
        }
        res.add(cur);

        return res;
    }

    /**
     * Compiles the list of nodes to an executable node structure. Note that the
     * node list has to make semantic sense - any nodes that follow a
     * terminating node (in the top-level list) will be ignored.
     *
     * @param astNodes The list of AST nodes to compile.
     * @param defaultNode The node to go to when a list of nodes does not end
     * with a terminating node.
     * @return The starting node for the execution.
     * @throws RuntimeException on errors in the code.
     */
    private Node buildNodes(List<? extends AstNode> astNodes, Node defaultNode) {

        try {
            return astNodes.isEmpty()
                    ? defaultNode
                    : C.head(astNodes).accept(new AstNode.Visitor<Node>() {

                @Override
                // build consider node from ast-consider-node 
                public Node visit(AstConsiderNode astNode) {
                    CompoundValue topValue = topLevelType.createInstance();
                    SetNodeValueBuilder valueBuilder = new SetNodeValueBuilder(topValue);

                    Node syntacticallyNext = buildNodes(C.tail(astNodes), defaultNode);
                    Node elseNode = syntacticallyNext;
                    if (astNode.getElseNode() != null) {
                        elseNode = buildNodes(astNode.getElseNode(), syntacticallyNext);
                    }
                    ConsiderNode res = new ConsiderNode(astNode.getId(), elseNode);

                    TagType slot = findSlot(astNode.getSlot(), topValue, valueBuilder);

                    if (slot instanceof AggregateType || slot instanceof AtomicType) {//consider node
                        for (AstConsiderAnswerSubNode astAns : astNode.getAnswers()) {
                            if (astAns.getAnswerList() == null) {
                                throw new RuntimeException(" (consider slot get only values)");
                            }
                            topValue = topLevelType.createInstance();
                            valueBuilder = new SetNodeValueBuilder(topValue);
                            AstSetNode.Assignment assignment;
                            if (slot instanceof AggregateType) {
                                assignment = new AstSetNode.AggregateAssignment(astNode.getSlot(), astAns.getAnswerList());
                            } else {
                                assignment = new AstSetNode.AtomicAssignment(astNode.getSlot(), astAns.getAnswerList().get(0).trim());
                            }

                            try {
                                assignment.accept(valueBuilder);
                            } catch (RuntimeException re) {
                                throw new RuntimeException(" (at node " + astNode + ")");
                            }
                            CompoundValue answer = topValue;
                            res.setNodeFor(ConsiderAnswer.Answer(answer), buildNodes(astAns.getSubGraph(), syntacticallyNext));
                        }
                    } else if (slot instanceof CompoundType) {//when node
                        for (AstConsiderAnswerSubNode astAns : astNode.getAnswers()) {
                            if (astAns.getAssignments() == null) {
                                throw new RuntimeException(" (compund slot get assignment )");
                            }
                            topValue = topLevelType.createInstance();
                            valueBuilder = new SetNodeValueBuilder(topValue);

                            List<AstSetNode.Assignment> assignments = astAns.getAssignments();

                            try {
                                for (AstSetNode.Assignment asnmnt : assignments) {
                                    asnmnt.accept(valueBuilder);
                                }
                            } catch (RuntimeException re) {
                                throw new RuntimeException(" (at node " + astNode + ")");
                            }
                            CompoundValue answer = topValue;
                            if(res.getNodeFor(ConsiderAnswer.Answer(answer))==null)
                            res.setNodeFor(ConsiderAnswer.Answer(answer), buildNodes(astAns.getSubGraph(), syntacticallyNext));

                        }
                    }
                    return product.add(res);
                }

                @Override
                public Node visit(AstAskNode astNode
                ) {
                    AskNode res = new AskNode(astNode.getId());
                    res.setText(astNode.getTextNode().getText());
                    if (astNode.getTerms() != null) {
                        astNode.getTerms().forEach(t -> res.addTerm(t.getTerm(), t.getExplanation()));
                    }

                    Node syntacticallyNext = buildNodes(C.tail(astNodes), defaultNode);

                    astNode.getAnswers().forEach(ansSubNode -> res.setNodeFor(Answer.get(ansSubNode.getAnswerText()),
                            buildNodes(ansSubNode.getSubGraph(), syntacticallyNext)));

                    impliedAnswers(res).forEach(ans -> res.setNodeFor(ans, syntacticallyNext));

                    return product.add(res);
                }

                @Override
                public Node visit(AstCallNode astNode
                ) {
                    CallNode callNode = new CallNode(astNode.getId());
                    callNode.setCalleeNodeId(astNode.getCalleeId());
                    callNode.setNextNode(buildNodes(C.tail(astNodes), defaultNode));
                    return product.add(callNode);
                }

                @Override
                public Node visit(AstSetNode astNode
                ) {
                    final CompoundValue topValue = topLevelType.createInstance();
                    SetNodeValueBuilder valueBuilder = new SetNodeValueBuilder(topValue);
                    try {
                        astNode.getAssignments().forEach(asnmnt -> asnmnt.accept(valueBuilder));
                    } catch (RuntimeException re) {
                        throw new RuntimeException(new BadSetInstructionException(re.getMessage() + " (at node " + astNode + ")", astNode));
                    }

                    final SetNode setNode = new SetNode(astNode.getId(), topValue);
                    setNode.setNextNode(buildNodes(C.tail(astNodes), defaultNode));
                    return product.add(setNode);
                }

                @Override
                public Node visit(AstTodoNode astNode
                ) {
                    final TodoNode todoNode = new TodoNode(astNode.getId(), astNode.getTodoText());
                    todoNode.setNextNode(buildNodes(C.tail(astNodes), defaultNode));
                    return product.add(todoNode);
                }

                @Override
                public Node visit(AstRejectNode astNode
                ) {
                    return product.add(new RejectNode(astNode.getId(), astNode.getReason()));
                }

                @Override
                public Node visit(AstEndNode astNode
                ) {
                    return product.add(new EndNode(astNode.getId()));
                }

            }
            );
        } catch (RuntimeException re) {
            Throwable cause = re.getCause();
            if ((cause != null) && (cause instanceof DataTagsParseException)) {
                DataTagsParseException pe = (DataTagsParseException) cause;
                if (pe.getOffendingNode() == null) {
                    pe.setOffendingNode(C.head(astNodes));
                }
                throw new RuntimeException(re.getMessage() + " (in node " + C.head(astNodes) + ")", pe);
            } else {
                throw re;
            }
        }
    }

    public List<? extends AstNode> getNodes() {
        return astNodes;
    }

    /**
     * Maps all unique slot suffixes to their fully qualified version. That is,
     * if we have:
     *
     * <code><pre>
     *  top/mid/a
     *  top/mid/b
     *  top/mid2/b
     * </pre></code>
     *
     * We end up with:      <code><pre>
     *  top/mid/a  => top/mid/a
     *  mid/a      => top/mid/a
     *  a          => top/mid/a
     *  top/mid/b  => top/mid/b
     *  mid/b      => top/mid/b
     *  top/mid2/b => top/mid2/b
     *  mid2/b     => top/mid2/b
     * </pre></code>
     *
     * @param topLevel the top level type to build from.
     */
    void buildTypeIndex(CompoundType topLevel) {
        topLevelType = topLevel;

        List<List<String>> fullyQualifiedNames = new LinkedList<>();
        // initial index
        topLevelType.accept(new TagType.VoidVisitor() {
            LinkedList<String> stack = new LinkedList<>();

            @Override
            public void visitAtomicTypeImpl(AtomicType t) {
                addType(t);
            }

            @Override
            public void visitAggregateTypeImpl(AggregateType t) {
                addType(t);
            }

            @Override
            public void visitTodoTypeImpl(ToDoType t) {
                addType(t);
            }

            @Override
            public void visitCompoundTypeImpl(CompoundType t) {
                stack.push(t.getName());
                t.getFieldTypes().forEach(tt -> tt.accept(this));
                stack.pop();
            }

            void addType(TagType tt) {
                stack.push(tt.getName());
                fullyQualifiedNames.add(C.reverse((List) stack));
                stack.pop();
            }
        });

        fullyQualifiedNames.forEach(n -> fullyQualifiedSlotName.put(n, n));

        // add abbreviations
        Set<List<String>> ambiguous = new HashSet<>();
        Map<List<String>, List<String>> newEntries = new HashMap<>();

        fullyQualifiedNames.forEach(slot -> {
            List<String> cur = C.tail(slot);
            while (!cur.isEmpty()) {
                if (fullyQualifiedSlotName.containsKey(cur) || newEntries.containsKey(cur)) {
                    ambiguous.add(cur);
                    break;
                } else {
                    newEntries.put(cur, slot);
                }
                cur = C.tail(cur);
            }
        });

        ambiguous.forEach(newEntries::remove);
        fullyQualifiedSlotName.putAll(newEntries);
    }

    TagType findSlot(List<String> astSlot, CompoundValue topValue, SetNodeValueBuilder valueBuilder) {
        TagType slot;

        if (astSlot == null || C.last(astSlot).equals(topLevelType.getName())) {
            slot = topLevelType;
        } else {
            try {
                final CompoundValue additionPoint = valueBuilder.descend(C.tail(fullyQualifiedSlotName.get(astSlot)), topValue);
                slot = additionPoint.getType().getTypeNamed(C.last(astSlot));
            } catch (RuntimeException re) {

                throw new RuntimeException("Tag not found");
            }
        }
        return slot;
    }

    /**
     * Nodes that have only a single "yes" of "no" answer, are considered to
     * implicitly have the reverse boolean answer as well. This method detects
     * that case and generates those answers.
     *
     * @param node
     * @return A list of implied answers (might be empty, never {@code null}).
     */
    List<Answer> impliedAnswers(AskNode node) {

        List<Answer> answers = node.getAnswers();
        if (answers.size() > 1) {
            return Collections.emptyList();
        }
        if (answers.isEmpty()) {
            return Arrays.asList(Answer.NO, Answer.YES); // special case, where both YES and NO lead to the same options. 
        }
        Answer onlyAns = answers.iterator().next();

        String ansText = onlyAns.getAnswerText().trim().toLowerCase();
        switch (ansText) {
            case "yes":
                return Collections.singletonList(Answer.NO);
            case "no":
                return Collections.singletonList(Answer.YES);
            default:
                return Collections.emptyList();
        }
    }

    /**
     * Ensures that all nodes have ids. Generates ids if needed.
     *
     * @param nodes the nodes that will have IDs.
     */
    private void addIds(List<? extends AstNode> nodes) {
        AstNode.NullVisitor idSupplier = new AstNode.NullVisitor() {

            @Override
            public void visitImpl(AstConsiderNode nd) throws DataTagsRuntimeException {
                if (nd.getId() == null) {
                    nd.setId(nodeIdProvider.nextId());
                }
                nd.getAnswers().forEach(ans -> addIds(ans.getSubGraph()));
                if (nd.getElseNode() != null) {
                    addIds(nd.getElseNode());
                }
            }

            @Override
            public void visitImpl(AstAskNode nd) throws DataTagsRuntimeException {
                if (nd.getId() == null) {
                    nd.setId(nodeIdProvider.nextId());
                }
                nd.getAnswers().forEach(ans -> addIds(ans.getSubGraph()));
            }

            @Override
            public void visitImpl(AstSetNode nd) throws DataTagsRuntimeException {
                if (nd.getId() == null) {
                    nd.setId(nodeIdProvider.nextId());
                }
            }

            @Override
            public void visitImpl(AstRejectNode nd) throws DataTagsRuntimeException {
                if (nd.getId() == null) {
                    nd.setId(nodeIdProvider.nextId());
                }
            }

            @Override
            public void visitImpl(AstCallNode nd) throws DataTagsRuntimeException {
                if (nd.getId() == null) {
                    nd.setId(nodeIdProvider.nextId());
                }
            }

            @Override
            public void visitImpl(AstTodoNode nd) throws DataTagsRuntimeException {
                if (nd.getId() == null) {
                    nd.setId(nodeIdProvider.nextId());
                }
            }

            @Override
            public void visitImpl(AstEndNode nd) throws DataTagsRuntimeException {
                if (nd.getId() == null) {
                    nd.setId(nodeIdProvider.nextId());
                }
            }
        };

        nodes.forEach(n -> n.accept(idSupplier));

    }

    /**
     * Builds a value based on the assignments visited.
     */
    private class SetNodeValueBuilder implements AstSetNode.Assignment.Visitor {

        private final CompoundValue topValue;

        public SetNodeValueBuilder(CompoundValue topValue) {
            this.topValue = topValue;
        }

        @Override
        public void visit(AstSetNode.AtomicAssignment aa) {
            final CompoundValue additionPoint = descend(C.tail(fullyQualifiedSlotName.get(aa.getSlot())), topValue);
            TagType valueType = additionPoint.getType().getTypeNamed(C.last(aa.getSlot()));
            if (valueType == null) {
                throw new RuntimeException("Type '" + additionPoint.getType().getName()
                        + "' does not have a field of type '" + C.last(aa.getSlot()) + "'");
            }
            valueType.accept(new TagType.VoidVisitor() {
                @Override
                public void visitAtomicTypeImpl(AtomicType t) {
                    AtomicValue value = t.valueOf(aa.getValue());
                    if (value == null) {
                        throw new RuntimeException("Field " + aa.getSlot() + " does not have a value " + aa.getValue());
                    }
                    additionPoint.set(value);
                }

                @Override
                public void visitAggregateTypeImpl(AggregateType t) {
                    throw new RuntimeException("Slot " + aa.getSlot() + " is aggregate, not atomic. Use ``+='' .");
                }

                @Override
                public void visitCompoundTypeImpl(CompoundType t) {
                    throw new RuntimeException("Slot " + aa.getSlot() + " is compound, not atomic. Can't assign values here.");
                }

                @Override
                public void visitTodoTypeImpl(ToDoType t) {
                    throw new RuntimeException("Slot " + aa.getSlot() + " is a placeholder. Can't assign values here.");
                }
            });
        }

        @Override
        public void visit(AstSetNode.AggregateAssignment aa) {
            final CompoundValue additionPoint = descend(C.tail(fullyQualifiedSlotName.get(aa.getSlot())), topValue);
            TagType valueType = additionPoint.getType().getTypeNamed(C.last(aa.getSlot()));
            if (valueType == null) {
                throw new RuntimeException("Type '" + additionPoint.getType().getName()
                        + "' does not have a field of type '" + C.last(aa.getSlot()));
            }
            valueType.accept(new TagType.VoidVisitor() {
                @Override
                public void visitAtomicTypeImpl(AtomicType t) {
                    throw new RuntimeException("Slot " + aa.getSlot() + " is aggregate, not atomic. Use ``+='' .");
                }

                @Override
                public void visitAggregateTypeImpl(AggregateType t) {
                    AggregateValue value = (AggregateValue) additionPoint.get(t);
                    if (value == null) {
                        value = t.createInstance();
                        additionPoint.set(value);
                    }
                    for (String val : aa.getValue()) {
                        value.add(t.getItemType().valueOf(val));
                    }
                }

                @Override
                public void visitCompoundTypeImpl(CompoundType t) {
                    throw new RuntimeException("Slot " + aa.getSlot() + " is compound, not atomic. Can't assign values here.");
                }

                @Override
                public void visitTodoTypeImpl(ToDoType t) {
                    throw new RuntimeException("Slot " + aa.getSlot() + " is a placeholder. Can't assign values here.");
                }
            });
        }

        /**
         * Descends the compound value tree, adding values as needed.
         *
         * @param pathRemainder the names of the fields along which we descend.
         * @param cVal the value we start the descend from
         * @return the compound value of the type pointed by the penultimate
         * item in {@code path}
         * @throws RuntimeException if the path is not descendable (i.e fields
         * don't exist or of the wrong type).
         */
        CompoundValue descend(List<String> pathRemainder, CompoundValue cVal) {
            if (pathRemainder.size() == 1) {
                return cVal;
            }
            CompoundType cType = cVal.getType();
            TagType nextTagType = cType.getTypeNamed(C.head(pathRemainder));
            if (nextTagType == null) {
                throw new RuntimeException("Type '" + cType.getName()
                        + "' does not have a field of type '" + C.head(pathRemainder));
            }

            return descend(C.tail(pathRemainder), nextTagType.accept(new TagType.Visitor<CompoundValue>() {
                @Override
                public CompoundValue visitSimpleType(AtomicType t) {
                    throw new RuntimeException("Type '" + t.getName()
                            + "' is not a compound type");
                }

                @Override
                public CompoundValue visitAggregateType(AggregateType t) {
                    throw new RuntimeException("Type '" + t.getName()
                            + "' is not a compound type");
                }

                @Override
                public CompoundValue visitTodoType(ToDoType t) {
                    throw new RuntimeException("Type '" + t.getName() + "' is not a compound type");
                }

                @Override
                public CompoundValue visitCompoundType(CompoundType t) {
                    if (cVal.get(t) == null) {
                        final CompoundValue newInstance = t.createInstance();
                        cVal.set(newInstance);
                    }
                    return (CompoundValue) cVal.get(t);
                }
            }));
        }

    };

    public URI getSource() {
        return source;
    }

    public void setSource(URI source) {
        this.source = source;
    }

}
