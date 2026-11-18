package com.codementor.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvidenceLocatorTest {

    private static final String DOCUMENT = """
            class Demo {
                int first() {
                    return items.size();
                }

                int second() {
                    if (items == null) {
                        return 0;
                    }
                    return items.size();
                }
            }
            """;

    @Test
    void findsSnippetWhoseWhitespaceDiffersFromTheDocument() {
        EvidenceLocator.Range range = EvidenceLocator.locate(
                DOCUMENT, "if (items == null) { return 0; }", 0);

        assertEquals("if (items == null) {\n            return 0;\n        }",
                DOCUMENT.substring(range.start(), range.end()));
    }

    @Test
    void prefersTheMatchInsideTheReviewedMethod() {
        int secondMethod = DOCUMENT.indexOf("int second()");

        EvidenceLocator.Range first = EvidenceLocator.locate(DOCUMENT, "return items.size();", 0);
        EvidenceLocator.Range preferred =
                EvidenceLocator.locate(DOCUMENT, "return items.size();", secondMethod);

        assertEquals(DOCUMENT.indexOf("return items.size();"), first.start());
        assertEquals(DOCUMENT.lastIndexOf("return items.size();"), preferred.start());
        assertEquals("return items.size();",
                DOCUMENT.substring(preferred.start(), preferred.end()));
    }

    @Test
    void fallsBackToTheWholeDocumentWhenNothingFollowsThePreferredOffset() {
        EvidenceLocator.Range range = EvidenceLocator.locate(
                DOCUMENT, "int first()", DOCUMENT.indexOf("int second()"));

        assertEquals(DOCUMENT.indexOf("int first()"), range.start());
    }

    @Test
    void returnsNullForMissingBlankOrOutOfRangeInput() {
        assertNull(EvidenceLocator.locate(DOCUMENT, "items.clear()", 0));
        assertNull(EvidenceLocator.locate(DOCUMENT, "   \n ", 0));
        assertNull(EvidenceLocator.locate(DOCUMENT, null, 0));
        assertNull(EvidenceLocator.locate(null, "x", 0));
        assertEquals(DOCUMENT.indexOf("class Demo"),
                EvidenceLocator.locate(DOCUMENT, "class Demo", 99_999).start());
    }
}
