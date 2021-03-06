/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.dac.explore.bi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.text.ParseException;

import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.glassfish.jersey.media.multipart.ContentDisposition;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.dremio.dac.explore.model.DatasetPath;
import com.dremio.dac.server.WebServer;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.record.BatchSchema;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;

/**
 * Unit tests for {@link TableauMessageBodyGenerator}
 */
@RunWith(Parameterized.class)
public class TestTableauMessageBodyGenerator {
  private final DatasetPath path;
  private final String tableName;

  @Parameters(name="{0}")
  public static final Object[] getTestCases() {
    return new Object[] {
      new String[] { "basic", "UNTITLED.tmp", "[UNTITLED].[tmp]" },
      new String[] { "subfolder", "spaceA.foo.tmp", "[spaceA.foo].[tmp]" },
      new String[] { "dot-in-name", "spaceA.\"tmp.json\"", "[spaceA].[tmp.json]" },
      new String[] { "home-dataset", "@dremio.tmp", "[@dremio].[tmp]" },
      new String[] { "weird-name", "spaceA.[foo][bar]", "[spaceA].[[foo]][bar]]]" },
      new String[] { "weird-schema", "spaceA.[whynot].tmp", "[spaceA.[whynot]]].[tmp]" }
    };
  }

  public TestTableauMessageBodyGenerator(String testName, String path, String tableName) {
    this.path = new DatasetPath(path);
    this.tableName = tableName;
  }

  @Test
  public void verifyOutput()
      throws IOException, SAXException, ParserConfigurationException, ParseException {
    Configuration configuration = mock(Configuration.class);
    DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setFullPathList(path.toPathList());
    TableauMessageBodyGenerator generator = new TableauMessageBodyGenerator(configuration, NodeEndpoint.newBuilder().setAddress("foo").setUserPort(12345).build());
    MultivaluedMap<String, Object> httpHeaders = new MultivaluedHashMap<>();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    assertTrue(generator.isWriteable(datasetConfig.getClass(), null, null, WebServer.MediaType.APPLICATION_TDS_TYPE));
    generator.writeTo(datasetConfig, DatasetConfig.class, null, new Annotation[] {}, WebServer.MediaType.APPLICATION_TDS_TYPE, httpHeaders, baos);

    // Convert the baos into a DOM Tree to verify content
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(baos.toByteArray()));

    assertEquals("", document.getDocumentElement().getAttribute("version"));

    NodeList connections = document.getDocumentElement().getElementsByTagName("connection");

    assertEquals(1, connections.getLength());
    Element connection = (Element) connections.item(0);
    assertEquals("genericodbc", connection.getAttribute("class"));
    assertEquals("Dremio Connector", connection.getAttribute("odbc-driver"));
    assertEquals("DREMIO", connection.getAttribute("dbname"));
    assertEquals(path.toParentPath(), connection.getAttribute("schema"));

    NodeList relations = connection.getElementsByTagName("relation");
    assertEquals(1, relations.getLength());
    Element relation = (Element) relations.item(0);
    assertEquals("table", relation.getAttribute("type"));
    assertEquals(tableName, relation.getAttribute("table"));

    // Also check that Content-Disposition header is set with a filename ending by tds
    ContentDisposition contentDisposition = new ContentDisposition((String) httpHeaders.getFirst(HttpHeaders.CONTENT_DISPOSITION));
    assertTrue("filename should end with .tds", contentDisposition.getFileName().endsWith(".tds"));
  }

  @Test
  public void verifyNativeOutput()
      throws IOException, SAXException, ParserConfigurationException, ParseException {
    Configuration configuration = mock(Configuration.class);
    DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setFullPathList(path.toPathList());

    // create a schema to test the metadata output for native connectors
    datasetConfig.setType(DatasetType.PHYSICAL_DATASET);
    BatchSchema schema = BatchSchema.newBuilder()
      .addField(new Field("string", FieldType.nullable(ArrowType.Utf8.INSTANCE), null))
      .addField(new Field("bool", FieldType.nullable(ArrowType.Bool.INSTANCE), null))
      .addField(new Field("decimal", FieldType.nullable(new ArrowType.Decimal(0, 0)), null))
      .addField(new Field("int", FieldType.nullable(new ArrowType.Int(8, false)), null))
      .addField(new Field("date", FieldType.nullable(new ArrowType.Date(DateUnit.MILLISECOND)), null))
      .addField(new Field("time", FieldType.nullable(new ArrowType.Time(TimeUnit.MILLISECOND, 8)), null))
      .build();
    datasetConfig.setRecordSchema(schema.toByteString());

    TableauMessageBodyGenerator generator = new TableauMessageBodyGenerator(configuration, NodeEndpoint.newBuilder().setAddress("foo").setUserPort(12345).build());
    MultivaluedMap<String, Object> httpHeaders = new MultivaluedHashMap<>();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    assertTrue(generator.isWriteable(datasetConfig.getClass(), null, null, WebServer.MediaType.APPLICATION_TDS_DRILL_TYPE));
    generator.writeTo(datasetConfig, DatasetConfig.class, null, new Annotation[] {}, WebServer.MediaType.APPLICATION_TDS_DRILL_TYPE, httpHeaders, baos);

    // Convert the baos into a DOM Tree to verify content
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(baos.toByteArray()));

    NodeList connections = document.getDocumentElement().getElementsByTagName("connection");

    assertEquals(1, connections.getLength());
    Element connection = (Element) connections.item(0);

    assertEquals("drill", connection.getAttribute("class"));
    assertEquals("Direct", connection.getAttribute("connection-type"));
    assertEquals("foo", connection.getAttribute("server"));
    assertEquals("12345", connection.getAttribute("port"));
    assertEquals(path.toParentPath(), connection.getAttribute("schema"));

    NodeList relations = connection.getElementsByTagName("relation");
    assertEquals(1, relations.getLength());
    Element relation = (Element) relations.item(0);
    assertEquals("table", relation.getAttribute("type"));
    assertEquals(tableName, relation.getAttribute("table"));

    // metadata tests
    NodeList metadataRecords = document.getDocumentElement().getElementsByTagName("metadata-record");

    assertEquals(metadataRecords.getLength(), schema.getFieldCount());
    assertEqualsMetadataRecord(metadataRecords.item(0), "[string]", "string");
    assertEqualsMetadataRecord(metadataRecords.item(1), "[bool]", "boolean");
    assertEqualsMetadataRecord(metadataRecords.item(2), "[decimal]", "real");
    assertEqualsMetadataRecord(metadataRecords.item(3), "[int]", "integer");
    assertEqualsMetadataRecord(metadataRecords.item(4), "[date]", "date");
    assertEqualsMetadataRecord(metadataRecords.item(5), "[time]", "datetime");

    // Also check that Content-Disposition header is set with a filename ending by tds
    ContentDisposition contentDisposition = new ContentDisposition((String) httpHeaders.getFirst(HttpHeaders.CONTENT_DISPOSITION));
    assertTrue("filename should end with .tds", contentDisposition.getFileName().endsWith(".tds"));
  }

  private void assertEqualsMetadataRecord(Node node, String fieldName, String fieldType) {
    Node child = node.getChildNodes().item(0);
    assertEquals(child.getNodeName(), "local-name");
    assertEquals(child.getTextContent(), fieldName);

    child = node.getChildNodes().item(1);
    assertEquals(child.getNodeName(), "local-type");
    assertEquals(child.getTextContent(), fieldType);
  }
}
