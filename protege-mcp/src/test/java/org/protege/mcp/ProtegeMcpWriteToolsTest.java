package org.protege.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
        when(modelManager.getOntologies()).thenAnswer(inv -> realManager.getOntologies());
        when(modelManager.isDirty(any(OWLOntology.class))).thenReturn(false);
        when(modelManager.isMutable(any(OWLOntology.class))).thenReturn(true);
        doAnswer(inv -> {
            realManager.applyChanges(inv.getArgument(0));
            return null;
        }).when(modelManager).applyChanges(anyList());
        // Delegate removeOntology to the real manager so ontology_reload works
        // (the V1.5 implementation uses ModelManager-level remove to invalidate
        // workspace caches; without delegation, reload would re-fail with
        // OWLOntologyAlreadyExistsException).
        doAnswer(inv -> {
            realManager.removeOntology((OWLOntology) inv.getArgument(0));
            return true;
        }).when(modelManager).removeOntology(any(OWLOntology.class));

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

    // -----------------------------------------------------------------------
    // V1.5 – class_delete / class_rename
    // -----------------------------------------------------------------------

    @Test
    public void classDelete_removesReferencingAxioms() throws Exception {
        OWLClass foo = df.getOWLClass(IRI.create("http://example.org/test#Foo"));
        OWLClass bar = df.getOWLClass(IRI.create("http://example.org/test#Bar"));
        realManager.applyChanges(java.util.Arrays.asList(
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(foo)),
                new AddAxiom(ontology, df.getOWLSubClassOfAxiom(foo, bar))));
        assertEquals(2, ontology.getAxiomCount());

        ObjectNode a = args();
        a.put("class_iri", "http://example.org/test#Foo");
        JsonNode r = call("class_delete", a);

        assertEquals(2, r.path("removed_axioms").asInt());
        assertEquals(0, ontology.getAxiomCount());
    }

    @Test
    public void classRename_propagatesAcrossAxioms() throws Exception {
        OWLClass foo = df.getOWLClass(IRI.create("http://example.org/test#Foo"));
        OWLClass bar = df.getOWLClass(IRI.create("http://example.org/test#Bar"));
        realManager.applyChanges(java.util.Arrays.asList(
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(foo)),
                new AddAxiom(ontology, df.getOWLSubClassOfAxiom(foo, bar))));

        ObjectNode a = args();
        a.put("old_iri", "http://example.org/test#Foo");
        a.put("new_iri", "http://example.org/test#Foo2");
        JsonNode r = call("class_rename", a);

        assertTrue(r.path("applied_changes").asInt() > 0);
        IRI oldI = IRI.create("http://example.org/test#Foo");
        IRI newI = IRI.create("http://example.org/test#Foo2");
        assertFalse(ontology.containsClassInSignature(oldI));
        assertTrue(ontology.containsClassInSignature(newI));
    }

    // -----------------------------------------------------------------------
    // V1.5 – object_property_*
    // -----------------------------------------------------------------------

    @Test
    public void objectPropertyCreate_withParent_addsSubPropertyAxiom() throws Exception {
        ObjectNode a = args();
        a.put("property_iri", "http://example.org/test#hasChild");
        a.put("parent_iri", "http://example.org/test#hasRelative");
        JsonNode r = call("object_property_create", a);

        assertEquals(2, r.path("changes").asInt());
        assertEquals(1, ontology.getAxioms(AxiomType.SUB_OBJECT_PROPERTY).size());
        assertTrue(ontology.containsObjectPropertyInSignature(
                IRI.create("http://example.org/test#hasChild")));
    }

    @Test
    public void objectPropertyDelete_removesReferencingAxioms() throws Exception {
        OWLObjectProperty p = df.getOWLObjectProperty(
                IRI.create("http://example.org/test#hasChild"));
        OWLObjectProperty parent = df.getOWLObjectProperty(
                IRI.create("http://example.org/test#hasRelative"));
        realManager.applyChanges(java.util.Arrays.asList(
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(p)),
                new AddAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(p, parent))));
        assertEquals(2, ontology.getAxiomCount());

        ObjectNode a = args();
        a.put("property_iri", "http://example.org/test#hasChild");
        JsonNode r = call("object_property_delete", a);

        assertEquals(2, r.path("removed_axioms").asInt());
        assertFalse(ontology.containsObjectPropertyInSignature(
                IRI.create("http://example.org/test#hasChild")));
    }

    @Test
    public void objectPropertyRename_propagates() throws Exception {
        OWLObjectProperty p = df.getOWLObjectProperty(
                IRI.create("http://example.org/test#hasChild"));
        realManager.applyChange(new AddAxiom(ontology, df.getOWLDeclarationAxiom(p)));

        ObjectNode a = args();
        a.put("old_iri", "http://example.org/test#hasChild");
        a.put("new_iri", "http://example.org/test#hasKid");
        JsonNode r = call("object_property_rename", a);

        assertTrue(r.path("applied_changes").asInt() > 0);
        assertFalse(ontology.containsObjectPropertyInSignature(
                IRI.create("http://example.org/test#hasChild")));
        assertTrue(ontology.containsObjectPropertyInSignature(
                IRI.create("http://example.org/test#hasKid")));
    }

    // -----------------------------------------------------------------------
    // V1.5 – data_property_*
    // -----------------------------------------------------------------------

    @Test
    public void dataPropertyCreate_addsDeclaration() throws Exception {
        ObjectNode a = args();
        a.put("property_iri", "http://example.org/test#age");
        JsonNode r = call("data_property_create", a);

        assertEquals(1, r.path("changes").asInt());
        assertTrue(ontology.containsDataPropertyInSignature(
                IRI.create("http://example.org/test#age")));
    }

    @Test
    public void dataPropertyDelete_removesReferencingAxioms() throws Exception {
        OWLDataProperty p = df.getOWLDataProperty(
                IRI.create("http://example.org/test#age"));
        realManager.applyChange(new AddAxiom(ontology, df.getOWLDeclarationAxiom(p)));
        assertEquals(1, ontology.getAxiomCount());

        ObjectNode a = args();
        a.put("property_iri", "http://example.org/test#age");
        JsonNode r = call("data_property_delete", a);

        assertEquals(1, r.path("removed_axioms").asInt());
        assertFalse(ontology.containsDataPropertyInSignature(
                IRI.create("http://example.org/test#age")));
    }

    @Test
    public void dataPropertyRename_propagates() throws Exception {
        OWLDataProperty p = df.getOWLDataProperty(
                IRI.create("http://example.org/test#age"));
        realManager.applyChange(new AddAxiom(ontology, df.getOWLDeclarationAxiom(p)));

        ObjectNode a = args();
        a.put("old_iri", "http://example.org/test#age");
        a.put("new_iri", "http://example.org/test#yearsOld");
        JsonNode r = call("data_property_rename", a);

        assertTrue(r.path("applied_changes").asInt() > 0);
        assertFalse(ontology.containsDataPropertyInSignature(
                IRI.create("http://example.org/test#age")));
        assertTrue(ontology.containsDataPropertyInSignature(
                IRI.create("http://example.org/test#yearsOld")));
    }

    // -----------------------------------------------------------------------
    // V1.5 – entity_annotate_set / entity_annotate_remove
    // -----------------------------------------------------------------------

    @Test
    public void entityAnnotateSet_replacesExistingLabel() throws Exception {
        IRI iri = IRI.create("http://example.org/test#Foo");
        OWLClass foo = df.getOWLClass(iri);
        OWLAnnotationProperty label = df.getRDFSLabel();
        realManager.applyChanges(java.util.Arrays.asList(
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(foo)),
                new AddAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                        label, iri, df.getOWLLiteral("Old")))));

        ObjectNode a = args();
        a.put("entity_iri", iri.toString());
        a.put("property", "rdfs:label");
        a.put("value", "New");
        JsonNode r = call("entity_annotate_set", a);

        assertEquals(1, r.path("removed").asInt());
        assertEquals(1, r.path("added").asInt());
        Set<OWLAnnotationAssertionAxiom> annotations = ontology.getAnnotationAssertionAxioms(iri);
        assertEquals(1, annotations.size());
        assertEquals("New",
                annotations.iterator().next().getValue().asLiteral().get().getLiteral());
    }

    @Test
    public void entityAnnotateSet_rejectsUnsupportedProperty() throws Exception {
        IRI iri = IRI.create("http://example.org/test#Foo");
        realManager.applyChange(new AddAxiom(ontology,
                df.getOWLDeclarationAxiom(df.getOWLClass(iri))));

        ObjectNode a = args();
        a.put("entity_iri", iri.toString());
        a.put("property", "rdfs:seeAlso");
        a.put("value", "x");
        try {
            call("entity_annotate_set", a);
            fail("Expected McpException");
        } catch (ProtegeMcpToolExecutor.McpException e) {
            assertEquals(-32602, e.getCode());
            assertTrue(e.getMessage().contains("rdfs:label"));
        }
    }

    @Test
    public void entityAnnotateRemove_removesMatchingAnnotations() throws Exception {
        IRI iri = IRI.create("http://example.org/test#Foo");
        OWLClass foo = df.getOWLClass(iri);
        OWLAnnotationProperty label = df.getRDFSLabel();
        OWLAnnotationProperty comment = df.getRDFSComment();
        realManager.applyChanges(java.util.Arrays.asList(
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(foo)),
                new AddAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                        label, iri, df.getOWLLiteral("L1"))),
                new AddAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                        label, iri, df.getOWLLiteral("L2"))),
                new AddAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                        comment, iri, df.getOWLLiteral("C1")))));

        ObjectNode a = args();
        a.put("entity_iri", iri.toString());
        a.put("property", "rdfs:label");
        JsonNode r = call("entity_annotate_remove", a);

        assertEquals(2, r.path("removed").asInt());
        // comment should still be there
        assertEquals(1, ontology.getAnnotationAssertionAxioms(iri).size());
    }

    // -----------------------------------------------------------------------
    // V1.5 – ontology_reload
    // -----------------------------------------------------------------------

    @Test
    public void ontologyReload_returnsAxiomCount() throws Exception {
        // Write a minimal RDF/XML doc with one declared class to a temp file,
        // point the in-memory ontology's document IRI at it, then reload.
        File tmp = File.createTempFile("protege-mcp-reload-", ".owl");
        tmp.deleteOnExit();
        String rdfXml = "<?xml version=\"1.0\"?>\n"
                + "<rdf:RDF xmlns=\"http://example.org/test#\"\n"
                + "    xml:base=\"http://example.org/test\"\n"
                + "    xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
                + "    xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n"
                + "    xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n"
                + "  <owl:Ontology rdf:about=\"http://example.org/test\"/>\n"
                + "  <owl:Class rdf:about=\"http://example.org/test#ReloadedClass\"/>\n"
                + "</rdf:RDF>\n";
        Files.write(tmp.toPath(), rdfXml.getBytes(StandardCharsets.UTF_8));
        realManager.setOntologyDocumentIRI(ontology, IRI.create(tmp.toURI()));

        JsonNode r = call("ontology_reload", args());

        assertTrue(r.path("reloaded").asBoolean());
        assertEquals(tmp.toURI().toString(), r.path("physical_uri").asText());
        // Reloaded ontology should contain at least the declaration axiom.
        assertTrue(r.path("axiom_count").asInt() >= 1);
        // The reloaded ontology must be reachable via realManager.
        OWLOntology reloaded = realManager.getOntology(IRI.create(ONT_IRI));
        assertNotNull(reloaded);
        assertTrue(reloaded.containsClassInSignature(
                IRI.create("http://example.org/test#ReloadedClass")));
    }

    // -----------------------------------------------------------------------
    // V1.5 – individual_delete / individual_rename
    // -----------------------------------------------------------------------

    @Test
    public void individualDelete_removesReferencingAxioms() throws Exception {
        IRI indIri = IRI.create("http://example.org/test#i1");
        IRI clsIri = IRI.create("http://example.org/test#A");
        org.semanticweb.owlapi.model.OWLNamedIndividual ind = df.getOWLNamedIndividual(indIri);
        OWLClass cls = df.getOWLClass(clsIri);
        realManager.applyChanges(java.util.Arrays.asList(
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(ind)),
                new AddAxiom(ontology, df.getOWLClassAssertionAxiom(cls, ind))));
        assertEquals(2, ontology.getAxiomCount());

        ObjectNode a = args();
        a.put("individual_iri", indIri.toString());
        JsonNode r = call("individual_delete", a);

        assertEquals(2, r.path("removed_axioms").asInt());
        assertFalse(ontology.containsIndividualInSignature(indIri));
        assertEquals(0, ontology.getAxioms(AxiomType.CLASS_ASSERTION).size());
    }

    @Test
    public void individualRename_propagates() throws Exception {
        IRI oldI = IRI.create("http://example.org/test#i1");
        IRI newI = IRI.create("http://example.org/test#i2");
        IRI clsIri = IRI.create("http://example.org/test#A");
        org.semanticweb.owlapi.model.OWLNamedIndividual ind = df.getOWLNamedIndividual(oldI);
        OWLClass cls = df.getOWLClass(clsIri);
        realManager.applyChanges(java.util.Arrays.asList(
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(ind)),
                new AddAxiom(ontology, df.getOWLClassAssertionAxiom(cls, ind))));

        ObjectNode a = args();
        a.put("old_iri", oldI.toString());
        a.put("new_iri", newI.toString());
        JsonNode r = call("individual_rename", a);

        assertTrue(r.path("applied_changes").asInt() > 0);
        assertFalse(ontology.containsIndividualInSignature(oldI));
        assertTrue(ontology.containsIndividualInSignature(newI));
    }

    // -----------------------------------------------------------------------
    // V1.5 – axiom_add / axiom_remove (Manchester syntax)
    // -----------------------------------------------------------------------

    @Test
    public void axiomAdd_acceptsManchesterClassAssertion() throws Exception {
        // Pre-declare Foo and Bar so the Manchester parser's short-form
        // entity checker can resolve them.
        OWLClass foo = df.getOWLClass(IRI.create("http://example.org/test#Foo"));
        OWLClass bar = df.getOWLClass(IRI.create("http://example.org/test#Bar"));
        realManager.applyChanges(java.util.Arrays.asList(
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(foo)),
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(bar))));

        ObjectNode a = args();
        a.put("axiom", "Foo SubClassOf Bar");
        JsonNode r = call("axiom_add", a);

        assertTrue(r.path("added").asBoolean());
        assertTrue(ontology.containsAxiom(df.getOWLSubClassOfAxiom(foo, bar)));
    }

    @Test
    public void axiomRemove_removesExistingAxiom() throws Exception {
        OWLClass foo = df.getOWLClass(IRI.create("http://example.org/test#Foo"));
        OWLClass bar = df.getOWLClass(IRI.create("http://example.org/test#Bar"));
        OWLAxiom sub = df.getOWLSubClassOfAxiom(foo, bar);
        realManager.applyChanges(java.util.Arrays.asList(
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(foo)),
                new AddAxiom(ontology, df.getOWLDeclarationAxiom(bar)),
                new AddAxiom(ontology, sub)));
        assertTrue(ontology.containsAxiom(sub));

        ObjectNode a = args();
        a.put("axiom", "Foo SubClassOf Bar");
        JsonNode r = call("axiom_remove", a);

        assertTrue(r.path("removed").asBoolean());
        assertFalse(ontology.containsAxiom(sub));
    }
}
