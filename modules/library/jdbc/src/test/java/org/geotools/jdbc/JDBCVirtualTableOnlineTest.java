package org.geotools.jdbc;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.geotools.data.FeatureSource;
import org.geotools.data.FeatureStore;
import org.geotools.data.Join;
import org.geotools.data.Query;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.factory.Hints;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.filter.FilterFactory;
import org.opengis.filter.Id;
import org.opengis.filter.sort.SortBy;
import org.opengis.filter.sort.SortOrder;

import com.vividsolutions.jts.geom.LineString;

/**
 * 
 *
 * @source $URL$
 */
public abstract class JDBCVirtualTableOnlineTest extends JDBCTestSupport {
    protected String dbSchemaName = null;


    @Override
    protected abstract JDBCDataStoreAPITestSetup createTestSetup();

    @Override
    protected void connect() throws Exception {
        super.connect();
        
        // a plain view without specs, used to check we guess the geometry field properly
        StringBuffer sb = new StringBuffer();
        sb.append("select * from ");
        if (dbSchemaName!=null) {
            dialect.encodeSchemaName(dbSchemaName, sb);
            sb.append(".");
        }
        dialect.encodeTableName(tname("river"), sb);
        VirtualTable vt = new VirtualTable("riverFull", sb.toString());
        dataStore.addVirtualTable(vt);
    
        // a first vt with a condition, computing a new field
        sb = new StringBuffer();
        sb.append("select ");
        dialect.encodeColumnName(aname("id"), sb);
        sb.append(", ");
        dialect.encodeColumnName(aname("geom"), sb);
        sb.append(", ");
        dialect.encodeColumnName(aname("river"), sb);
        sb.append(", ");
        dialect.encodeColumnName(aname("flow"), sb);
        sb.append(" * 2 as ");
        dialect.encodeColumnName(aname("doubleFlow"), sb);
        sb.append(" from ");
        if (dbSchemaName!=null) {
            dialect.encodeSchemaName(dbSchemaName, sb);
            sb.append(".");
        }
        dialect.encodeTableName(tname("river"), sb);
        sb.append(" where ");
        dialect.encodeColumnName(aname("flow"), sb);
        sb.append(" > 4");
        vt = new VirtualTable("riverReduced", sb.toString());
        vt.addGeometryMetadatata(aname("geom"), LineString.class, 4326);
        dataStore.addVirtualTable(vt);
        
        // same vt, this time with a sql comment
        sb.append("\n--This is a comment");
        vt = new VirtualTable("riverReducedComment", sb.toString());
        vt.addGeometryMetadatata(aname("geom"), LineString.class, 4326);
        dataStore.addVirtualTable(vt);    
        
        // the same vt, but with a id specification
        vt = new VirtualTable("riverReducedPk", sb.toString());
        vt.addGeometryMetadatata(aname("geom"), LineString.class, 4326);
        vt.setPrimaryKeyColumns(Arrays.asList(aname("id")));
        dataStore.addVirtualTable(vt);    
        
        // a final vt with some parameters
        sb = new StringBuffer();
        sb.append("select ");
        dialect.encodeColumnName(aname("id"), sb);
        sb.append(", ");
        dialect.encodeColumnName(aname("geom"), sb);
        sb.append(", ");
        dialect.encodeColumnName("flow", sb);
        sb.append(" * %mul% as ");
        dialect.encodeColumnName("mulflow", sb);
        sb.append(" from ");
        if (dbSchemaName!=null) {
            dialect.encodeSchemaName(dbSchemaName, sb);
            sb.append(".");
        }
        dialect.encodeTableName(tname("river"), sb);
        sb.append(" %where%");
        vt = new VirtualTable("riverParam", sb.toString());
        vt.addGeometryMetadatata(aname("geom"), LineString.class, 4326);
        vt.addParameter(new VirtualTableParameter("mul", "1", new RegexpValidator("[\\d\\.e\\+-]+")));
        vt.addParameter(new VirtualTableParameter("where", ""));
        dataStore.addVirtualTable(vt);
    }
    
    public void testGuessGeometry() throws Exception {
        SimpleFeatureType type = dataStore.getSchema("riverFull");
        assertNotNull(type);
        
        assertNotNull(type.getGeometryDescriptor());
    }

    public void testRiverReducedSchema() throws Exception {
        SimpleFeatureType type = dataStore.getSchema("riverReduced");
        assertNotNull(type);
        
        checkRiverReduced(type);
    }
    
    public void testRiverReducedCommentSchema() throws Exception {
        SimpleFeatureType type = dataStore.getSchema("riverReducedComment");
        assertNotNull(type);
        
        checkRiverReduced(type);
    }

    private void checkRiverReduced(SimpleFeatureType type) {
        assertEquals(4, type.getAttributeCount());
        AttributeDescriptor id = type.getDescriptor(aname("id"));
        assertTrue(Number.class.isAssignableFrom(id.getType().getBinding()));
        GeometryDescriptor geom = type.getGeometryDescriptor();
        assertEquals(aname("geom"), geom.getLocalName());
        AttributeDescriptor river = type.getDescriptor(aname("river"));
        assertEquals(String.class, river.getType().getBinding());
        AttributeDescriptor doubleFlow = type.getDescriptor(aname("doubleFlow"));
        assertTrue(Number.class.isAssignableFrom(doubleFlow.getType().getBinding()));
        
        // check srid and dimension are set as expected
        assertEquals(4326, type.getGeometryDescriptor().getUserData().get(JDBCDataStore.JDBC_NATIVE_SRID));
        assertEquals(2, type.getGeometryDescriptor().getUserData().get(Hints.COORDINATE_DIMENSION));
    }
    
    public void testListAll() throws Exception {
        FeatureSource fsView = dataStore.getFeatureSource("riverReduced");
        assertFalse(fsView instanceof FeatureStore);

        assertEquals(1, fsView.getCount(Query.ALL));
        
        FeatureIterator<SimpleFeature> it = null;
        try {
            it = fsView.getFeatures().features();
            
            assertTrue(it.hasNext());
            SimpleFeature sf = it.next();
            assertEquals("rv1", sf.getAttribute(aname("river")));
            assertEquals(9.0, ((Number) sf.getAttribute(aname("doubleFlow"))).doubleValue(), 0.1);
            assertFalse(it.hasNext());
        } finally {
            it.close();
        }
    }
    
    public void testBounds() throws Exception {
        FeatureSource fsView = dataStore.getFeatureSource("riverReduced");
        ReferencedEnvelope env = fsView.getBounds();
        assertNotNull(env);
    }
    
    public void testInvalidQuery() throws Exception {
        String sql = dataStore.getVirtualTables().get("riverReduced").getSql();
        
        VirtualTable vt = new VirtualTable("riverPolluted", "SOME EXTRA GARBAGE " + sql);
        vt.addGeometryMetadatata("geom", LineString.class, -1);
        try {
            dataStore.addVirtualTable(vt);
            fail("Should have failed with invalid sql definition");
        } catch(IOException e) {
            // ok, that's what we expected
        }
    }
    
    public void testGetFeatureId() throws Exception {
        FeatureSource fsView = dataStore.getFeatureSource("riverReducedPk");
        assertFalse(fsView instanceof FeatureStore);

        assertEquals(1, fsView.getCount(Query.ALL));
        
        FeatureIterator<SimpleFeature> it = null;
        try {
            it = fsView.getFeatures().features();
            
            assertTrue(it.hasNext());
            SimpleFeature sf = it.next();
            // check the primary key is build out of the fid attribute
            assertEquals("riverReducedPk.0", sf.getID());
        } finally {
            it.close();
        }
    }
    
    public void testGetFeatureById() throws Exception {
        FeatureSource fsView = dataStore.getFeatureSource("riverReducedPk");
        assertFalse(fsView instanceof FeatureStore);
        
        // the problem is actually in pk computation
        PrimaryKey pk = dataStore.getPrimaryKey((SimpleFeatureType) fsView.getSchema());
        assertEquals("riverReducedPk", pk.getTableName());
        assertEquals(1, pk.getColumns().size());
        PrimaryKeyColumn col = pk.getColumns().get(0);
        assertEquals(aname("id"), col.getName());
        assertTrue(Number.class.isAssignableFrom(col.getType()));
        
        FilterFactory ff = CommonFactoryFinder.getFilterFactory(null);
        Id filter = ff.id(Collections.singleton(ff.featureId("riverReducedPk.0")));
        
        assertEquals(1, fsView.getCount(new Query(null, filter)));
    }
    
    public void testWhereParam() throws Exception {
        FeatureSource fsView = dataStore.getFeatureSource("riverParam");
        
        // by default we get everything
        assertEquals(2, fsView.getCount(Query.ALL));
        
        // let's try filtering a bit dynamically
        Query q = new Query(Query.ALL);
        StringBuffer sb = new StringBuffer();
        sb.append(" where ");
        dialect.encodeColumnName(aname("flow"), sb);
        sb.append(" > 4");  
        q.setHints(new Hints(Hints.VIRTUAL_TABLE_PARAMETERS, Collections.singletonMap("where", sb.toString())));
        assertEquals(1, fsView.getCount(q));
    }
    
    public void testMulParamValid() throws Exception {
        FilterFactory ff = CommonFactoryFinder.getFilterFactory(null);
        FeatureSource fsView = dataStore.getFeatureSource("riverParam");
        
        // let's change the mul param
        Query q = new Query(Query.ALL);
        q.setHints(new Hints(Hints.VIRTUAL_TABLE_PARAMETERS, Collections.singletonMap("mul", "10")));
        q.setSortBy(new SortBy[] {ff.sort(aname("mulflow"), SortOrder.ASCENDING)});
        FeatureIterator fi = null;
        try {
            fi = fsView.getFeatures(q).features();
            assertTrue(fi.hasNext());
            SimpleFeature f = (SimpleFeature) fi.next();
            assertEquals(30.0, ((Number) f.getAttribute(aname("mulflow"))).doubleValue(), 0.1);
            assertTrue(fi.hasNext());
            f = (SimpleFeature) fi.next();
            assertEquals(45.0, ((Number) f.getAttribute(aname("mulflow"))).doubleValue(), 0.1);
        } finally {
            fi.close();
        }
        
    }
    
    public void testMulParamInvalid() throws Exception {
        FeatureSource fsView = dataStore.getFeatureSource("riverParam");
        
        // let's set an invalid mul param
        Query q = new Query(Query.ALL);
        q.setHints(new Hints(Hints.VIRTUAL_TABLE_PARAMETERS, Collections.singletonMap("mul", "abc")));
        try {
            fsView.getFeatures(q).features();
            fail("Should have thrown an exception!");
        } catch(Exception e) {
            // fine
        }
    }

    public void testInvalidView() throws Exception {
        StringBuffer sb = new StringBuffer();
        sb.append("select ");
        dialect.encodeColumnName(null, aname("id"), sb);
        sb.append(", ");
        dialect.encodeColumnName(null, aname("flow"), sb);
        sb.append(" from ");
        if (dbSchemaName != null) {
            dialect.encodeSchemaName(dbSchemaName, sb);
            sb.append(".");
        }
        dialect.encodeTableName(tname("river"), sb);
        VirtualTable vt = new VirtualTable("invalid_attribute", sb.toString());

        Handler handler = new Handler() {
            @Override
            public synchronized void publish(LogRecord record) {
                fail("We should not have received any log statement");
            }

            @Override
            public void flush() {
                // nothing to do
            }

            @Override
            public void close() throws SecurityException {
                // nothing to do
            }
        };
        handler.setLevel(Level.WARNING);
        Logger logger = Logging.getLogger("org.geotools.jdbc");
        Level oldLevel = logger.getLevel();

        SimpleFeatureIterator fi = null;
        try {
            logger.setLevel(java.util.logging.Level.SEVERE);
            logger.addHandler(handler);
            dataStore.createVirtualTable(vt);
            ContentFeatureSource fs = dataStore.getFeatureSource("invalid_attribute");

            // now hack the sql view definition to inject an invalid column name,
            // it's easier than having to alter the column name in the db to make the existing
            // view invalid
            sb.setLength(0);
            dialect.encodeColumnName(null, aname("not_valid"), sb);
            String notValid = sb.toString();
            sb.setLength(0);
            dialect.encodeColumnName(null, aname("flow"), sb);
            String flow = sb.toString();
            dataStore.virtualTables.get("invalid_attribute").sql = vt.sql.replace(flow, notValid);

            fi = fs.getFeatures().features();
            fail("We should not have gotten here, we were supposed to get a sql exception");
        } catch (RuntimeException e) {
            // fine, this is expected
            assertTrue(e.getCause() instanceof IOException);
        } finally {
            if (fi != null) {
                fi.close();
            }
            dataStore.dropVirtualTable("invalid_attribute");

            // shake the vm to make it run the finalizers
            System.gc();
            System.runFinalization();

            // reset the handlers
            logger.setLevel(oldLevel);
            logger.removeHandler(handler);
        }
    }

    public void testJoinViews() throws Exception {
        Query joinQuery = new Query("riverFull");
        FilterFactory ff = dataStore.getFilterFactory();
        Join join = new Join("riverReduced", ff.equal(ff.property("a." + aname("river")),
                ff.property(aname("river")), false));
        join.setAlias("a");
        joinQuery.getJoins().add(join);

        // get the two feature sources
        ContentFeatureSource fsFull = dataStore.getFeatureSource("riverFull");
        ContentFeatureSource fsReduced = dataStore.getFeatureSource("riverReduced");

        // check count
        int expectedCount = fsReduced.getCount(Query.ALL);
        int count = fsFull.getCount(joinQuery);

        assertEquals(expectedCount, count);
    }
}
