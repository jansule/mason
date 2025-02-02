/*
 * Copyright 2011 by Mark Coletti, Keith Sullivan, Sean Luke, and
 * George Mason University Mason University Licensed under the Academic
 * Free License version 3.0
 *
 * See the file "LICENSE" for more information
 *
 * $Id$
 */
package sim.io.geo;

import com.vividsolutions.jts.algorithm.CGAlgorithms;
//import org.geotools.data.shapefile.ShapefileDataStore;
import com.vividsolutions.jts.geom.*;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import sim.field.geo.GeomVectorField;
import sim.util.Bag;
import sim.util.geo.AttributeValue;
import sim.util.geo.MasonGeometry;



/**
 * A native Java importer to read ERSI shapefile data into the GeomVectorField.
 * We assume the input file follows the standard ESRI shapefile format.
 */
public class ShapeFileImporter
{

    /** Not meant to be instantiated
    */
    private ShapeFileImporter()
    {
    }


    // Shape types included in ESRI Shapefiles. Not all of these are currently supported.

    final static int NULL_SHAPE = 0;
    final static int POINT = 1;
    final static int POLYLINE = 3;
    final static int POLYGON = 5;
    final static int MULTIPOINT = 8;
    final static int POINTZ = 11;
    final static int POLYLINEZ = 13;
    final static int POLYGONZ = 15;
    final static int MULTIPOINTZ = 18;
    final static int POINTM = 21;
    final static int POLYLINEM = 23;
    final static int POLYGONM = 25;
    final static int MULTIPOINTM = 28;
    final static int MULTIPATCH = 31;



    public static boolean isSupported(int shapeType)
    {
        switch (shapeType)
        {
            case POINT:
            case POLYLINE:
            case POLYGON:
            case POINTZ:
                return true;
            default:
                return false;	// no other types are currently supported
        }
    }



    private static String typeToString(int shapeType)
    {
        switch (shapeType)
        {
            case NULL_SHAPE:
                return "NULL_SHAPE";
            case POINT:
                return "POINT";
            case POLYLINE:
                return "POLYLINE";
            case POLYGON:
                return "POLYGON";
            case MULTIPOINT:
                return "MULTIPOINT";
            case POINTZ:
                return "POINTZ";
            case POLYLINEZ:
                return "POLYLINEZ";
            case POLYGONZ:
                return "POLYGONZ";
            case MULTIPOINTZ:
                return "MULTIPOINTZ";
            case POINTM:
                return "POINTM";
            case POLYLINEM:
                return "POLYLINEM";
            case POLYGONM:
                return "POLYGONM";
            case MULTIPOINTM:
                return "MULTIPOINTM";
            case MULTIPATCH:
                return "MULTIPATCH";
            default:
                return "UNKNOWN";
        }
    }




    /** Create a polygon from an array of LinearRings.
     *
     * If there is only one ring the function will create and return a simple
     * polygon. If there are multiple rings, the function checks to see if any
     * of them are holes (which are in counter-clockwise order) and if so, it
     * creates a polygon with holes.  If there are no holes, it creates and
     * returns a multi-part polygon.
     *
     */
    private static Geometry createPolygon(LinearRing[] parts)
    {
        GeometryFactory geomFactory = new GeometryFactory();

        if (parts.length == 1)
        {
            return geomFactory.createPolygon(parts[0], null);
        }

        ArrayList<LinearRing> shells = new ArrayList<LinearRing>();
        ArrayList<LinearRing> holes = new ArrayList<LinearRing>();

        for (int i = 0; i < parts.length; i++)
        {
            if (CGAlgorithms.isCCW(parts[i].getCoordinates()))
            {
                holes.add(parts[i]);
            } else
            {
                shells.add(parts[i]);
            }
        }

        // This will contain any holes within a given polygon
        LinearRing [] holesArray = null;

        if (! holes.isEmpty())
        {
            holesArray = new LinearRing[holes.size()];
            holes.toArray(holesArray);
        }

        if (shells.size() == 1)
        { // single polygon

            // It's ok if holesArray is null
            return geomFactory.createPolygon(shells.get(0), holesArray);
        }
        else
        { // mutipolygon
            Polygon[] poly = new Polygon[shells.size()];

            for (int i = 0; i < shells.size(); i++)
            {
                poly[i] = geomFactory.createPolygon(parts[i], holesArray);
            }

            return geomFactory.createMultiPolygon(poly);
        }
    }



    /**
     * Wrapper function which creates a new array of LinearRings and calls
     * the other function.
     */
    private static Geometry createPolygon(Geometry[] parts)
    {
        LinearRing[] rings = new LinearRing[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            rings[i] = (LinearRing) parts[i];
        }

        return createPolygon(rings);
    }


    /** Populate field from the shape file given in fileName
     *
     * @param shpFile to be read from
     * @param dbFile to be read from
     * @param field to contain read in data
     * @throws FileNotFoundException
     */
    public static void read(final URL shpFile, final URL dbFile, GeomVectorField field) throws FileNotFoundException, IOException, Exception
    {
        read(shpFile, dbFile, field, null, MasonGeometry.class);
    }

    public static void read(String shpPath, String dbPath, GeomVectorField field) throws FileNotFoundException, IOException, Exception
    {
        read((new URI(shpPath)).toURL(), (new URI(dbPath)).toURL(), field, null, MasonGeometry.class);
    }



    /** Populate field from the shape file given in fileName
     *
     * @param shpFile to be read from
     * @param dbFile to be read from
     * @param field to contain read in data
     * @param masked dictates the subset of attributes we want
     * @throws FileNotFoundException
     */
    public static void read(final URL shpFile, final URL dbFile, GeomVectorField field, final Bag masked) throws FileNotFoundException, IOException, Exception
    {
        read(shpFile, dbFile, field, masked, MasonGeometry.class);
    }

    public static void read(String shpPath, String dbPath, GeomVectorField field, final Bag masked) throws FileNotFoundException, IOException, Exception
    {
        read((new URI(shpPath)).toURL(), (new URI(dbPath)).toURL(), field, masked, MasonGeometry.class);
    }


    /** Populate field from the shape file given in fileName
     *
     * @param shpFile to be read from
     * @param dbFile to be read from
     * @param field to contain read in data
     * @param masonGeometryClass allows us to over-ride the default MasonGeometry wrapper
     * @throws FileNotFoundException
     */
    public static void read(final URL shpFile, final URL dbFile, GeomVectorField field, Class<?> masonGeometryClass) throws FileNotFoundException, IOException, Exception
    {
        read(shpFile, dbFile, field, null, masonGeometryClass);
    }

    public static void read(String shpPath, String dbPath, GeomVectorField field, Class<?> masonGeometryClass) throws FileNotFoundException, IOException, Exception
    {
        read((new URI(shpPath)).toURL(), (new URI(dbPath)).toURL(), field, null, masonGeometryClass);
    }


    public static void read(Class theClass, String shpFilePathRelativeToClass, String dbFilePathRelativeToClass, GeomVectorField field, final Bag masked, Class<?> masonGeometryClass) throws IOException, Exception
    {
        read(theClass.getResource(shpFilePathRelativeToClass), theClass.getResource(dbFilePathRelativeToClass), field, masked, masonGeometryClass);
    }


    public static void seek(InputStream in, int num) throws RuntimeException, IOException
    {
        byte[] b = new byte[num];
        int chk = in.read(b);
        if(chk != num || chk == -1){
            throw new IOException("Bad seek, chk = " + chk);
        }
    }

    public static boolean littleEndian = java.nio.ByteOrder.nativeOrder().equals(java.nio.ByteOrder.LITTLE_ENDIAN);		 // for example, intel is little endian

    public static byte readByte(InputStream stream, boolean littleEndian) throws RuntimeException, IOException{
        byte[] b = new byte[1];
        int chk = stream.read(b);
        if (chk != b.length || chk == -1){
            throw new IOException("readByte early termination, chk = " + chk);
        }
        return b[0];
    }

    public static short readShort(InputStream stream, boolean littleEndian) throws RuntimeException, IOException{
        byte[] b = new byte[2];
        int chk = stream.read(b);
        if (chk != b.length || chk == -1){
            throw new IOException("readShort early termination, chk = " + chk);
        }
        return ByteBuffer.wrap(b).order((littleEndian)? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getShort();
    }

    public static int readInt(InputStream stream, boolean littleEndian) throws RuntimeException, IOException{
        byte[] b = new byte[4];
        int chk = stream.read(b);
        if (chk != b.length || chk == -1){
            throw new IOException("readInt early termination, chk = " + chk);
        }
        return ByteBuffer.wrap(b).order((littleEndian) ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getInt();
    }

    public static double readDouble(InputStream stream, boolean littleEndian) throws RuntimeException, IOException{
        byte[] b = new byte[8];
        int chk = stream.read(b);
        if (chk != b.length || chk == -1){
            throw new IOException("readDouble early termination, chk = " + chk);
        }
        return ByteBuffer.wrap(b).order((littleEndian)? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getDouble();
    }

    public static InputStream open(URL url) throws IllegalArgumentException, RuntimeException, IOException
    {
        if(url == null)
            throw new IllegalArgumentException("url is null; file is probably not found");
        InputStream urlStream = new BufferedInputStream(url.openStream());
        if(urlStream == null)
            throw new RuntimeException("Cannot load URL " + url);
        return urlStream;
    }


    public static void read(final URL shpFile, final URL dbFile, GeomVectorField field, final Bag masked, Class<?> masonGeometryClass) throws FileNotFoundException, IOException, Exception
    {
        if (! MasonGeometry.class.isAssignableFrom(masonGeometryClass))  // Not a subclass? No go
        {
            throw new IllegalArgumentException("masonGeometryClass not a MasonGeometry class or subclass");
        }

        try
        {
            class FieldDirEntry
            {
                public String name;
                public int fieldSize;
            }
            InputStream shpFileInputStream;
            InputStream dbFileInputStream;

            try {
                shpFileInputStream = open(shpFile); 
                dbFileInputStream = open(dbFile);
            } catch (IllegalArgumentException e) {
                System.err.println("Either your shpFile or dbFile is missing!");
                throw e;
            }

            // The header size is 8 bytes in, and is little endian
            seek(dbFileInputStream, 8);

            int headerSize = (int) readShort(dbFileInputStream, true);
            int recordSize = (int) readShort(dbFileInputStream, true);
            int fieldCnt = (short) ((headerSize - 1) / 32 - 1);

            FieldDirEntry fields[] = new FieldDirEntry[fieldCnt];
            seek(dbFileInputStream, 32); // Skip 32 ahead.

            byte c[] = new byte[32];
            char type[] = new char[fieldCnt];
            int length;

            for (int i = 0; i < fieldCnt; i++)
            {
                dbFileInputStream.read(c, 0, 11);
                int j = 0;
                for (j = 0; j < 12 && c[j] != 0; j++); // skip to first unwritten byte
                String name = new String(c, 0, j);
                type[i] = (char) readByte(dbFileInputStream, true);
                fields[i] = new FieldDirEntry();
                fields[i].name = name;
                dbFileInputStream.read(c, 0, 4);  // data address
                byte b = readByte(dbFileInputStream, true);
                length = (b >= 0) ? (int) b : 256 + (int) b; // Allow 0?
                fields[i].fieldSize = length;

                seek(dbFileInputStream, 15);
            }
            dbFileInputStream.close();
            dbFileInputStream = open(dbFile); // Reopen for new seekin'
            seek(dbFileInputStream, headerSize); // Skip the initial stuff.


            GeometryFactory geomFactory = new GeometryFactory();

            seek(shpFileInputStream, 100);

            while (shpFileInputStream.available() > 0)
            {
                // advance past two int: recordNumber and recordLength
                //byteBuf.position(byteBuf.position() + 8);

                //byteBuf.order(ByteOrder.LITTLE_ENDIAN);
                seek(shpFileInputStream, 8);

                int recordType = readInt(shpFileInputStream, true);

                if (!isSupported(recordType))
                {
                    System.out.println("Error: ShapeFileImporter.ingest(...): ShapeType " + typeToString(recordType) + " not supported.");
                    return;		// all shapes are the same type so don't bother reading any more
                }

                // Read the attributes

                byte r[] = new byte[recordSize];
                int chk = dbFileInputStream.read(r);

                // Why is this start1 = 1?
                int start1 = 1;

                // Contains all the attribute values keyed by name that will eventually
                // be copied over to a corresponding MasonGeometry wrapper.
                Map<String, AttributeValue> attributes = new HashMap<String, AttributeValue>(fieldCnt);


                for (int k = 0; k < fieldCnt; k++)
                {
                    // It used to be that we'd just flag attributes not in
                    // the mask Bag as hidden; however, now we just don't
                    // bother adding it to the MasonGeometry.  If the user
                    // really wanted that attribute, they'd have added it to
                    // the mask in the first place
                    //                    if (masked != null && ! masked.contains(fields[k].name))
                    //                    {
                    //                        fld.setHidden(true);
                    //                    } else
                    //                    {
                    //                        fld.setHidden(false);
                    //                    }

                    // If the user bothered specifying a mask and the current
                    // attribute, as indexed by 'k', is NOT in the mask, then
                    // merrily skip on to the next attribute
                    if (masked != null && ! masked.contains(fields[k].name))
                    {
                        // But before we skip, ensure that we wind the pointer
                        // to the start of the next attribute value.
                        start1 += fields[k].fieldSize;

                        continue;
                    }
                    String rawAttributeValue = new String(r, start1, fields[k].fieldSize);
                    rawAttributeValue = rawAttributeValue.trim();

                    AttributeValue attributeValue = new AttributeValue();

                    if ( rawAttributeValue.isEmpty() )
                    {
                        // If we've gotten no data for this, then just add the
                        // empty string.
                        attributeValue.setString(rawAttributeValue);
                    } else {
                        switch(type[k]){ // Numeric case
                            case 'N':
                                if (rawAttributeValue.length() == 0) attributeValue.setString("0");
                                if (rawAttributeValue.indexOf('.') != -1)
                                    attributeValue.setDouble(Double.valueOf(rawAttributeValue));
                                else
                                    attributeValue.setInteger(Integer.valueOf(rawAttributeValue));
                                break;
                            case 'L': // Logical
                                attributeValue.setValue(Boolean.valueOf(rawAttributeValue));
                                break;
                            case 'F': // Float
                                attributeValue.setValue(Double.valueOf(rawAttributeValue));
                                break;
                            default:
                                attributeValue.setString(rawAttributeValue);
                                break;
                        }
                    }
                    attributes.put(fields[k].name, attributeValue);
                    start1 += fields[k].fieldSize;
                }

                // Read the shape
                Geometry geom = null;
                Coordinate pt;
                switch(recordType){
                    case POINT:
                        pt = new Coordinate(readDouble(shpFileInputStream, true), readDouble(shpFileInputStream, true));
                        geom = geomFactory.createPoint(pt);
                        break;
                    case POINTZ:
                        pt = new Coordinate(readDouble(shpFileInputStream, true), readDouble(shpFileInputStream, true), readDouble(shpFileInputStream, true));
                        geom = geomFactory.createPoint(pt);
                        break;
                    case POLYLINE:
                    case POLYGON:
                        // advance past four doubles: minX, minY, maxX, maxY
                        seek(shpFileInputStream, 32);

                        int numParts = (int)readInt(shpFileInputStream, true);
                        int numPoints = (int)readInt(shpFileInputStream, true); 

                        // get the array of part indices
                        int partIndicies[] = new int[numParts];
                        for (int i = 0; i < numParts; i++)
                        {
                            partIndicies[i] = readInt(shpFileInputStream, true);
                        }

                        // get the array of points
                        Coordinate pointsArray[] = new Coordinate[numPoints];
                        for (int i = 0; i < numPoints; i++)
                        {
                            pointsArray[i] = new Coordinate(readDouble(shpFileInputStream, true), readDouble(shpFileInputStream, true));
                        }

                        Geometry[] parts = new Geometry[numParts];

                        for (int i = 0; i < numParts; i++)
                        {
                            int start = partIndicies[i];
                            int end = numPoints;
                            if (i < numParts - 1)
                            {
                                end = partIndicies[i + 1];
                            }
                            int size = end - start;
                            Coordinate coords[] = new Coordinate[size];

                            for (int j = 0; j < size; j++)
                            {
                                coords[j] = new Coordinate(pointsArray[start + j]);
                            }

                            if (recordType == POLYLINE)
                                parts[i] = geomFactory.createLineString(coords);
                            else
                                parts[i] = geomFactory.createLinearRing(coords);
                        }
                        if (recordType == POLYLINE)
                        {
                            LineString[] ls = new LineString[numParts];
                            for (int i = 0; i < numParts; i++)
                            {
                                ls[i] = (LineString) parts[i];
                            }
                            if (numParts == 1)
                            {
                                geom = parts[0];
                            } else
                            {
                                geom = geomFactory.createMultiLineString(ls);
                            }
                        } else	// polygon
                        {
                            geom = createPolygon(parts);
                        }
                        break;
                    default:
                        System.err.println("Unknown shape type in " + recordType);
                }


                if (geom != null)
                {
                    // The user *may* have created their own MasonGeometry
                    // class, so use the given masonGeometry class; by
                    // default it's MasonGeometry.
                    MasonGeometry masonGeometry = (MasonGeometry) masonGeometryClass.newInstance();
                    masonGeometry.geometry = geom;

                    if (!attributes.isEmpty())
                    {
                        masonGeometry.addAttributes(attributes);
                    }

                    field.addGeometry(masonGeometry);
                }
            }
            dbFileInputStream.close();
            shpFileInputStream.close();
        }
        catch (IOException e)
        {
            System.out.println("Error in ShapeFileImporter!!");
            System.out.println("SHP filename: " + shpFile.getPath() + "; DB filename: " + dbFile.getPath());
            //            e.printStackTrace();

            throw e;
        }
    }





    /** Populate field from the shape file given in fileName
     *
     * @param shpFile to be read from
     * @param field is GeomVectorField that will contain the ShapeFile's contents
     * @param masked dictates the subset of attributes we want
     * @param masonGeometryClass allows us to over-ride the default MasonGeometry wrapper
     * @throws FileNotFoundException if unable to open shape file
     * @throws IOException if problem reading files
     *
     */
    public static void readOld(final URL shpFile, final URL dbFile, GeomVectorField field, final Bag masked, Class<?> masonGeometryClass) throws FileNotFoundException, IOException, Exception
    {
        if (shpFile == null)
        {
            throw new IllegalArgumentException("shpFile is null; likely file not found");
        }

        if (! MasonGeometry.class.isAssignableFrom(masonGeometryClass))
        {
            throw new IllegalArgumentException("masonGeometryClass not a MasonGeometry class or subclass");
        }


        try
        {
            FileInputStream shpFileInputStream = new FileInputStream(shpFile.getFile());

            if (shpFileInputStream == null)
            {
                throw new FileNotFoundException(shpFile.getFile());
            }

            FileChannel channel = shpFileInputStream.getChannel();
            ByteBuffer byteBuf = channel.map(FileChannel.MapMode.READ_ONLY, 0, (int) channel.size());
            channel.close();

            // Database file name is same as shape file name, except with '.dbf' extension
            String dbfFilename = shpFile.getFile().substring(0, shpFile.getFile().lastIndexOf('.')) + ".dbf";

            FileInputStream dbFileInputStream = new FileInputStream(dbfFilename);

            FileChannel dbChannel = dbFileInputStream.getChannel();
            ByteBuffer dbBuffer = dbChannel.map(FileChannel.MapMode.READ_ONLY, 0, (int) dbChannel.size());
            dbChannel.close();

            dbBuffer.order(ByteOrder.LITTLE_ENDIAN);
            int headerSize = dbBuffer.getShort(8);
            int recordSize = dbBuffer.getShort(10);

            int fieldCnt = (short) ((headerSize - 1) / 32 - 1);

            // Corresponds to a dBase field directory entry
            class FieldDirEntry
            {
                public String name;
                public int fieldSize;
            }

            FieldDirEntry fields[] = new FieldDirEntry[fieldCnt];

            RandomAccessFile inFile = new RandomAccessFile(dbfFilename, "r");

            if (inFile == null)
            {
                throw new FileNotFoundException(dbfFilename);
            }

            inFile.seek(32);

            byte c[] = new byte[32];
            char type[] = new char[fieldCnt];
            int length;

            for (int i = 0; i < fieldCnt; i++)
            {
                inFile.readFully(c, 0, 11);

                int j = 0;

                for (j = 0; j < 12 && c[j] != 0; j++);

                String name = new String(c, 0, j);

                type[i] = (char) inFile.readByte();

                fields[i] = new FieldDirEntry();

                fields[i].name = name;

                inFile.read(c, 0, 4);  // data address

                byte b = inFile.readByte();

                if (b > 0)
                {
                    length = (int) b;
                } else
                {
                    length = 256 + (int) b;
                }

                fields[i].fieldSize = length;

                inFile.skipBytes(15);
            }

            inFile.seek(0);
            inFile.skipBytes(headerSize);


            GeometryFactory geomFactory = new GeometryFactory();


            // advance to the first record
            byteBuf.position(100);

            while (byteBuf.hasRemaining())
            {
                // advance past two int: recordNumber and recordLength
                byteBuf.position(byteBuf.position() + 8);

                byteBuf.order(ByteOrder.LITTLE_ENDIAN);

                int recordType = byteBuf.getInt();

                if (!isSupported(recordType))
                {
                    System.out.println("Error: ShapeFileImporter.ingest(...): ShapeType " + typeToString(recordType) + " not supported.");
                    return;		// all shapes are the same type so don't bother reading any more
                }

                // Read the attributes

                byte r[] = new byte[recordSize];
                inFile.read(r);

                int start1 = 1;

                // Contains all the attribute values keyed by name that will eventually
                // be copied over to a corresponding MasonGeometry wrapper.
                Map<String, AttributeValue> attributes = new HashMap<String, AttributeValue>(fieldCnt);

                //attributeInfo = new ArrayList<AttributeValue>();

                for (int k = 0; k < fieldCnt; k++)
                {
                    // It used to be that we'd just flag attributes not in
                    // the mask Bag as hidden; however, now we just don't
                    // bother adding it to the MasonGeometry.  If the user
                    // really wanted that attribute, they'd have added it to
                    // the mask in the first place
                    //                    if (masked != null && ! masked.contains(fields[k].name))
                    //                    {
                    //                        fld.setHidden(true);
                    //                    } else
                    //                    {
                    //                        fld.setHidden(false);
                    //                    }

                    // If the user bothered specifying a mask and the current
                    // attribute, as indexed by 'k', is NOT in the mask, then
                    // merrily skip on to the next attribute
                    if (masked != null && ! masked.contains(fields[k].name))
                    {
                        // But before we skip, ensure that we wind the pointer
                        // to the start of the next attribute value.
                        start1 += fields[k].fieldSize;

                        continue;
                    }

                    String rawAttributeValue = new String(r, start1, fields[k].fieldSize);
                    rawAttributeValue = rawAttributeValue.trim();

                    AttributeValue attributeValue = new AttributeValue();

                    if ( rawAttributeValue.isEmpty() )
                    {
                        // If we've gotten no data for this, then just add the
                        // empty string.
                        attributeValue.setString(rawAttributeValue);
                    }
                    else if (type[k] == 'N') // Numeric
                    {
                        if (rawAttributeValue.length() == 0)
                        {
                            attributeValue.setString("0");
                        }
                        if (rawAttributeValue.indexOf('.') != -1)
                        {
                            attributeValue.setDouble(Double.valueOf(rawAttributeValue));
                        } else
                        {
                            attributeValue.setInteger(Integer.valueOf(rawAttributeValue));
                        }
                    } else if (type[k] == 'L') // Logical
                    {
                        attributeValue.setValue(Boolean.valueOf(rawAttributeValue));
                    } else if (type[k] == 'F') // Floating point
                    {
                        attributeValue.setValue(Double.valueOf(rawAttributeValue));
                    }
                    else
                    {
                        attributeValue.setString(rawAttributeValue);
                    }

                    attributes.put(fields[k].name, attributeValue);

                    start1 += fields[k].fieldSize;
                }

                // Read the shape

                Geometry geom = null;

                if (recordType == POINT)
                {
                    Coordinate pt = new Coordinate(byteBuf.getDouble(), byteBuf.getDouble());
                    geom = geomFactory.createPoint(pt);
                }
                else if (recordType == POINTZ)
                {
                    Coordinate pt = new Coordinate(byteBuf.getDouble(), byteBuf.getDouble(), byteBuf.getDouble());

                    // Skip over the "measure" which we don't use.
                    // Actually, this is an optional field that most don't
                    // implement these days, so no need to skip over that
                    // which doesn't exist.
                    // XXX (Is there a way to detect that the M field exists?)
                    //                    byteBuf.position(byteBuf.position() + 8);

                    geom = geomFactory.createPoint(pt);
                } else if (recordType == POLYLINE || recordType == POLYGON)
                {
                    // advance past four doubles: minX, minY, maxX, maxY
                    byteBuf.position(byteBuf.position() + 32);

                    int numParts = byteBuf.getInt();
                    int numPoints = byteBuf.getInt();

                    // get the array of part indices
                    int partIndicies[] = new int[numParts];
                    for (int i = 0; i < numParts; i++)
                    {
                        partIndicies[i] = byteBuf.getInt();
                    }

                    // get the array of points
                    Coordinate pointsArray[] = new Coordinate[numPoints];
                    for (int i = 0; i < numPoints; i++)
                    {
                        pointsArray[i] = new Coordinate(byteBuf.getDouble(), byteBuf.getDouble());
                    }

                    Geometry[] parts = new Geometry[numParts];

                    for (int i = 0; i < numParts; i++)
                    {
                        int start = partIndicies[i];
                        int end = numPoints;
                        if (i < numParts - 1)
                        {
                            end = partIndicies[i + 1];
                        }
                        int size = end - start;
                        Coordinate coords[] = new Coordinate[size];

                        for (int j = 0; j < size; j++)
                        {
                            coords[j] = new Coordinate(pointsArray[start + j]);
                        }

                        if (recordType == POLYLINE)
                        {
                            parts[i] = geomFactory.createLineString(coords);
                        } else
                        {
                            parts[i] = geomFactory.createLinearRing(coords);
                        }
                    }
                    if (recordType == POLYLINE)
                    {
                        LineString[] ls = new LineString[numParts];
                        for (int i = 0; i < numParts; i++)
                        {
                            ls[i] = (LineString) parts[i];
                        }
                        if (numParts == 1)
                        {
                            geom = parts[0];
                        } else
                        {
                            geom = geomFactory.createMultiLineString(ls);
                        }
                    } else	// polygon
                    {
                        geom = createPolygon(parts);
                    }
                } else
                {
                    System.err.println("Unknown shape type in " + recordType);
                }

                if (geom != null)
                {
                    // The user *may* have created their own MasonGeometry
                    // class, so use the given masonGeometry class; by
                    // default it's MasonGeometry.
                    MasonGeometry masonGeometry = (MasonGeometry) masonGeometryClass.newInstance();
                    masonGeometry.geometry = geom;

                    if (!attributes.isEmpty())
                    {
                        masonGeometry.addAttributes(attributes);
                    }

                    field.addGeometry(masonGeometry);
                }
            }
        }
        catch (IOException e)
        {
            System.out.println("Error in ShapeFileImporter!!");
            System.out.println("SHP filename: " + shpFile);
            //            e.printStackTrace();

            throw e;
        }
    }

}
