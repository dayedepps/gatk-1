package org.broadinstitute.sting.utils.sam;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.broadinstitute.sting.BaseTest;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMFileHeader;

import java.util.ArrayList;
import java.util.List;


/*
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

/**
 * @author aaron
 *         <p/>
 *         Class ArtificialSAMFileWriter
 *         <p/>
 *         Test out the ArtificialSAMFileWriter class
 */
public class ArtificialSAMFileWriterTest extends BaseTest {

    /** the artificial sam writer */
    private ArtificialSAMFileWriter writer;
    private SAMFileHeader header;
    private final int startChr = 1;
    private final int numChr = 2;
    private final int chrSize = 100;

    @Before
    public void before() {
        writer = new ArtificialSAMFileWriter();
        header = ArtificialSamUtils.createArtificialSamHeader(numChr, startChr, chrSize);
    }

    @Test
    public void testBasicCount() {
        for (int x = 0; x < 100; x++) {
            SAMRecord rec = ArtificialSamUtils.createArtificialRead(header, String.valueOf(x), 1, x, ArtificialSamUtils.DEFAULT_READ_LENGTH);
            writer.addAlignment(rec);
        }
        assertEquals(100, writer.getRecords().size());

    }

    @Test
    public void testReadName() {
        List<String> names = new ArrayList<String>();

        for (int x = 0; x < 100; x++) {
            names.add(String.valueOf(x));
            SAMRecord rec = ArtificialSamUtils.createArtificialRead(header, String.valueOf(x), 1, x, ArtificialSamUtils.DEFAULT_READ_LENGTH);
            writer.addAlignment(rec);
        }
        assertEquals(100, writer.getRecords().size());

        // check the names
        for (int x = 0; x < 100; x++) {
            assertTrue(names.get(x).equals(writer.getRecords().get(x).getReadName()));
        }

    }

    @Test
    public void testClose() {
        writer.close();
        assertTrue(writer.isClosed());
    }
}
