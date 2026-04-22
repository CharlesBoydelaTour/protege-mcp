package org.protege.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the sandbox-first / dry_run / direct / sandbox_commit semantics
 * of the write tools exposed by {@link ProtegeMcpToolExecutor}.
 *
 * <p>
 * The executor is exercised through its public {@link
 * ProtegeMcpToolExecutor#execute(String, JsonNode)} entry point. A test
 * subclass overrides the package-private {@code requireModelManager()} hook
 * to inject a Mockito-backed {@link OWLModelManager} that delegates to a
 * real in-memory OWL ontology built with the OWL API.
 */
public class ProtegeMcpWriteToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ONT_IRI = "http://example.org/test";

    private OWLOntologyManager realManager;
    private OWLOntology ontology;
    private OWLDataFactory df;
    private OWLModelManager modelManager;
    private ProtegeMcpToolExecutor executor;

    @Before
    public void setUp() throws Exception {
        realManager = OWLManager.createOWLOntologyManager();
        df = realManager.getOWLDataFactory();
        ontology = realManager.createOntology(IRI.create(ONT_IRI));

        modelManager = mock(OWLModelManager.class);
        when(modelManager.getOWLDataFactory()).thenReturn(df);
        when(modelManager.getOWLOntologyManager()).thenReturn(realManager);
        when(modelManager.getOntologies()).thenReturn(Collections.singleton(ontology));
        when(modelManager.isDirty(any(OWLOntology.class))).thenReturn(false);
        when(modelManager.isMutable(any(OWLOntology.class))).thenReturn(true);
        doAnswer(inv -> {
            realManager.applyChanges(inv.getArgument(0));
            return null;
        }).when(modelManager).applyChanges(anyList());

        final OWLModelManager mm = modelManager;
        executor = new ProtegeMcpToolExecutor() {
            @Override
            OWLModelManager requireModelManager() {
                return mm;
            }
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ObjectNode args() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("ontology_id", ONT_IRI);
        return n;
    }

    private JsonNode call(String tool, ObjectNode a) throws Exception {
        return executor.execute(tool, a);
    }

    private int pendingCount() throws Exception {
        return call("workspace_status", args()).path("pending_changes").asInt();
    }

    private boolean sandboxPresent() throws Exception {
        return call("workspace_status", args()).path("sandbox_present").asBoolean();
    }

    // -----------------------------------------------------------------------
    // class_create
    // -----------------------------------------------------------------------

    @Test
    public void classCreate_default_isSandboxed() throws Exception {
        ObjectNode a = args();
        a.put("class_iri", "http://example.org/test#Foo");

        JsonNode r = call("class_create", a);

        assertEquals("http://example.org/test#Foo", r.path("class_iri").asText());
        assertEquals(1, r.path("changes").asInt());
        assertFalse(r.path("dry_run").asBoolean());
        assertTrue(r.path("sandboxed").asBoolean());
        assertEquals(0, ontology.getAxiomCount());
        assertEquals(1, pendingCount());

        // Now commit and verify ontology mutation + sandbox cleared.
        JsonNode commit = call("sandbox_commit", args());
        assertEquals(1, commit.path("committed_changes").asInt());
        assertFalse(commit.path("dry_run").asBoolean());
        assertEquals(1, ontology.getAxiomCount());
        assertFalse(sandboxPresent());

        OWLAxiom axiom = ontology.getAxioms().iterator().next();
        assertTrue(axiom instanceof OWLDeclarationAxiom);
    }

    @Test
    public void classCreate_dryRun_doesNotChangeAnything() throws Exception {
        ObjectNode a = args();
        a.put("class_iri", "http://example.org/test#Foo");
        a.put("dry_run", true);

        JsonNode r = call("class_create", a);

        assertTrue(r.path("dry_run").asBoolean());
        assertFalse(r.path("sandboxed").asBoolean());
        assertEquals(0, ontology.getAxiomCount());
        assertEquals(0, pendingCount());
        assertFalse(sandboxPresent());
    }

    @Test
    public void classCreate_direct_appliesImmediately() throws Exception {
        ObjectNode a = args();
        a.put("class_iri", "http://example.org/test#Foo");
        a.put("direct", true);

        JsonNode r = call("class_create", a);

        assertEquals(1, r.path("changes").asInt());
        assertFalse(r.path("sandboxed").asBoolean());
        assertEquals(1, ontology.getAxiomCount());
        assertFalse(sandboxPresent());
    }

    @Test
    public void classCreate_withParents_queuesDeclarationAndSubclass() throws Exception {
        ObjectNode a = args();
        a.put("class_iri", "http://example.org/test#Foo");
        ArrayNode parents = a.putArray("parent_class_iris");
        parents.add("http://example.org/test#Bar");

        JsonNode r = call("class_create", a);

        assertEquals(2, r.path("changes").asInt());
        assertEquals(2, pendingCount());
        assertEquals(0, ontology.getAxiomCount());

        call("sandbox_commit", args());
        assertEquals(2, ontology.getAxiomCount());
        assertEquals(1, ontology.getAxioms(AxiomType.SUBCLASS_OF).size());
        assertEquals(1, ontology.getAxioms(AxiomType.DECLARATION).size());
    }

    // -----------------------------------------------------------------------
    // property_create – one test per kind
    // -----------------------------------------------------------------------

    @Test
    public void propertyCreate_object_declaresObjectProperty() throws Exception {
        ObjectNode a = args();
        a.put("property_iri", "http://example.org/test#hasFoo");
        a.put("property_kind", "object");

        JsonNode r = call("property_create", a);

        assertEquals("object", r.path("kind").asText());
        assertEquals(1, pendingCount());
        call("sandbox_commit", args());
        assertEquals(1, ontology.getObjectPropertiesInSignature().size());
        assertEquals(0, ontology.getDataPropertiesInSignature().size());
        assertEquals(0, ontology.getAnnotationPropertiesInSignature().size());
    }

    @Test
    public void propertyCreate_data_declaresDataProperty() throws Exception {
        ObjectNode a = args();
        a.put("property_iri", "http://example.org/test#age");
        a.put("property_kind", "data");

        JsonNode r = call("property_create", a);

        assertEquals("data", r.path("kind").asText());
        call("sandbox_commit", args());
        assertEquals(1, ontology.getDataPropertiesInSignature().size());
        assertEquals(0, ontology.getObjectPropertiesInSignature().size());
    }

    @Test
    public void propertyCreate_annotation_declaresAnnotationProperty() throws Exception {
        ObjectNode a = args();
        a.put("property_iri", "http://example.org/test#note");
        a.put("property_kind", "annotation");

        JsonNode r = call("property_create", a);

        assertEquals("annotation", r.path("kind").asText());
        call("sandbox_commit", args());
        // Filter out built-in annotation properties (none added by createOntology
        // here, but be defensive).
        Set<org.semanticweb.owlapi.model.OWLAnnotationProperty> ap = ontology.getAnnotationPropertiesInSignature();
        assertEquals(1, ap.size());
        assertEquals("http://example.org/test#note",
                ap.iterator().next().getIRI().toString());
    }

    // -----------------------------------------------------------------------
    // individual_create
    // -----------------------------------------------------------------------

    @Test
    public void individualCreate_withTypes_queuesDeclarationAndAssertions() throws Exception {
        ObjectNode a = args();
        a.put("individual_iri", "http://example.org/test#i1");
        ArrayNode types = a.putArray("type_iris");
        types.add("http://example.org/test#A");
        types.add("http://example.org/test#B");

        JsonNode r = call("individual_create", a);

        assertEquals(3, r.path("changes").asInt()); // declaration + 2 type asserts
        assertEquals(3, pendingCount());

        call("sandbox_commit", args());
        assertEquals(1, ontology.getAxioms(AxiomType.DECLARATION).size());
        Set<OWLClassAssertionAxiom> asserts = ontology.getAxioms(AxiomType.CLASS_ASSERTION);
        assertEquals(2, asserts.size());
    }

    // -----------------------------------------------------------------------
    // individual_assert_type
    // -----------------------------------------------------------------------

    @Test
    public void individualAssertType_queuesSingleAssertion() throws Exception {
        ObjectNode a = args();
        a.put("individual_iri", "http://example.org/test#i1");
        a.put("class_iri", "http://example.org/test#A");

        JsonNode r = call("individual_assert_type", a);

        assertEquals("http://example.org/test#A", r.path("type_iri").asText());
        assertEquals(1, pendingCount());

        call("sandbox_commit", args());
        Set<OWLClassAssertionAxiom> asserts = ontology.getAxioms(AxiomType.CLASS_ASSERTION);
        assertEquals(1, asserts.size());
    }

    // -----------------------------------------------------------------------
    // annotation_set
    // -----------------------------------------------------------------------

    @Test
    public void annotationSet_queuesAnnotationAssertion_andCommitApplies() throws Exception {
        ObjectNode a = args();
        a.put("target_iri", "http://example.org/test#Foo");
        a.put("property_iri", "http://www.w3.org/2000/01/rdf-schema#label");
        a.put("value", "Foo Label");

        JsonNode r = call("annotation_set", a);

        assertEquals("Foo Label", r.path("value").asText());
        assertEquals(1, pendingCount());

        call("sandbox_commit", args());
        Set<OWLAnnotationAssertionAxiom> ann = ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION);
        assertEquals(1, ann.size());
        OWLAnnotationAssertionAxiom got = ann.iterator().next();
        assertEquals("http://example.org/test#Foo", got.getSubject().toString());
        assertTrue(got.getValue().asLiteral().isPresent());
        assertEquals("Foo Label", got.getValue().asLiteral().get().getLiteral());
    }

    // -----------------------------------------------------------------------
    // sandbox_commit edge cases
    // -----------------------------------------------------------------------

    @Test
    public void sandboxCommit_emptySandbox_returnsZero() throws Exception {
        JsonNode r = call("sandbox_commit", args());
        assertEquals(0, r.path("committed_changes").asInt());
        assertFalse(r.path("dry_run").asBoolean());
        assertEquals(0, ontology.getAxiomCount());
    }

    @Test
    public void sandboxCommit_dryRun_reportsCountWithoutApplyingOrClearing() throws Exception {
        ObjectNode a = args();
        a.put("class_iri", "http://example.org/test#Foo");
        call("class_create", a);
        assertEquals(1, pendingCount());

        ObjectNode commitArgs = args();
        commitArgs.put("dry_run", true);
        JsonNode r = call("sandbox_commit", commitArgs);

        assertEquals(1, r.path("committed_changes").asInt());
        assertTrue(r.path("dry_run").asBoolean());
        assertEquals(0, ontology.getAxiomCount());
        assertTrue(sandboxPresent());
        assertEquals(1, pendingCount());
    }
}
