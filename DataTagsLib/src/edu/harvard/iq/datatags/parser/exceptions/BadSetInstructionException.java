package edu.harvard.iq.datatags.parser.exceptions;

import edu.harvard.iq.datatags.model.types.TagValueLookupResult;
import edu.harvard.iq.datatags.parser.decisiongraph.references.AstSetNode;

/**
 *
 * @author michael
 */
public class BadSetInstructionException extends DataTagsParseException {
    private final TagValueLookupResult badResult;
    private final AstSetNode offendingNode;
    
    public BadSetInstructionException(TagValueLookupResult res, AstSetNode anOffendingNode) {
        super(null, "Bad set instruction: " + res );
        badResult = res;
        offendingNode = anOffendingNode;
    }

    public TagValueLookupResult getBadResult() {
        return badResult;
    }

    public AstSetNode getOffendingNode() {
        return offendingNode;
    }
    
}
