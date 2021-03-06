/*
* Copyright 2012-2016 Broad Institute, Inc.
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
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.gatk.utils;

import htsjdk.samtools.SAMSequenceDictionary;
import org.apache.log4j.Logger;
import org.broadinstitute.gatk.utils.exceptions.ReviewedGATKException;
import org.broadinstitute.gatk.utils.interval.IntervalMergingRule;
import org.broadinstitute.gatk.utils.interval.IntervalUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 *         <p/>
 *         Class GenomeLocCollection
 *         <p/>
 *         a set of genome locations. This collection is self sorting,
 *         and will merge genome locations that are overlapping. The remove function
 *         will also remove a region from the list, if the region to remove is a
 *         partial interval of a region in the collection it will remove the region from
 *         that element.
 *
 * @author aaron
 * Date: May 22, 2009
 * Time: 10:54:40 AM
 */
public class GenomeLocSortedSet extends AbstractSet<GenomeLoc> {
    private static Logger logger = Logger.getLogger(GenomeLocSortedSet.class);

    private GenomeLocParser genomeLocParser;

    // our private storage for the GenomeLoc's
    private final List<GenomeLoc> mArray = new ArrayList<>();

    // cache this to make overlap checking much more efficient
    private int previousOverlapSearchIndex = -1;

    /**
     * Create a new, empty GenomeLocSortedSet
     *
     * @param parser a non-null the parser we use to create genome locs
     */
    public GenomeLocSortedSet(final GenomeLocParser parser) {
        if ( parser == null ) throw new IllegalArgumentException("parser cannot be null");
        this.genomeLocParser = parser;
    }

    /**
     * Create a new GenomeLocSortedSet containing location e
     *
     * @param parser a non-null the parser we use to create genome locs
     * @param e a single genome locs to add to this set
     */
    public GenomeLocSortedSet(final GenomeLocParser parser, final GenomeLoc e) {
        this(parser);
        add(e);
    }

    /**
     * Create a new GenomeLocSortedSet containing locations l
     *
     * The elements in l can be in any order, and can be overlapping.  They will be sorted first and
     * overlapping (but not contiguous) elements will be merged
     *
     * @param parser a non-null the parser we use to create genome locs
     * @param l a collection of genome locs to add to this set
     */
    public GenomeLocSortedSet(final GenomeLocParser parser, final Collection<GenomeLoc> l) {
        this(parser);

        final ArrayList<GenomeLoc> sorted = new ArrayList<>(l);
        Collections.sort(sorted);
        mArray.addAll(IntervalUtils.mergeIntervalLocations(sorted, IntervalMergingRule.OVERLAPPING_ONLY));
    }

    /**
     * Gets the GenomeLocParser used to create this sorted set.
     * @return The parser.  Will never be null.
     */
    public GenomeLocParser getGenomeLocParser() {
        return genomeLocParser;
    }

    /**
     * get an iterator over this collection
     *
     * @return an iterator<GenomeLoc>
     */
    public Iterator<GenomeLoc> iterator() {
        return mArray.iterator();
    }

    /**
     * return the size of the collection
     *
     * @return the size of the collection
     */
    public int size() {
        return mArray.size();
    }

    /**
     * Return the size, in bp, of the genomic regions by all of the regions in this set
     * @return size in bp of the covered regions
     */
    public long coveredSize() {
        long s = 0;
        for ( GenomeLoc e : this )
            s += e.size();
        return s;
    }

    /**
     * Return the number of bps before loc in the sorted set
     *
     * @param loc the location before which we are counting bases
     * @return the number of base pairs over all previous intervals
     */
    public long sizeBeforeLoc(GenomeLoc loc) {
        long s = 0;

        for ( GenomeLoc e : this ) {
            if ( e.isBefore(loc) )
                s += e.size();
            else if ( e.isPast(loc) )
                break; // we are done
            else // loc is inside of s
                s += loc.getStart() - e.getStart();
        }

        return s;
    }

    /**
     * determine if the collection is empty
     *
     * @return true if we have no elements
     */
    public boolean isEmpty() {
        return mArray.isEmpty();
    }

    /**
     * Determine if the given loc overlaps any loc in the sorted set
     *
     * @param loc the location to test
     * @return trip if the location overlaps any loc
     */
    public boolean overlaps(final GenomeLoc loc) {
        // edge condition
        if ( mArray.isEmpty() )
            return false;

        // use the cached version first
        if ( previousOverlapSearchIndex != -1 && overlapsAtOrImmediatelyAfterCachedIndex(loc, true) )
            return true;

        // update the cached index
        previousOverlapSearchIndex = Collections.binarySearch(mArray, loc);

        // if it matches an interval exactly, we are done
        if ( previousOverlapSearchIndex >= 0 )
            return true;

        // check whether it overlaps the interval before or after the insertion point
        previousOverlapSearchIndex = Math.max(0, -1 * previousOverlapSearchIndex - 2);
        return overlapsAtOrImmediatelyAfterCachedIndex(loc, false);
    }

    private boolean overlapsAtOrImmediatelyAfterCachedIndex(final GenomeLoc loc, final boolean updateCachedIndex) {
        // check the cached entry
        if ( mArray.get(previousOverlapSearchIndex).overlapsP(loc) )
            return true;

        // check the entry after the cached entry since we may have moved to it
        boolean returnValue = false;
        if ( previousOverlapSearchIndex < mArray.size() - 1 ) {
            returnValue = mArray.get(previousOverlapSearchIndex + 1).overlapsP(loc);
            if ( updateCachedIndex )
                previousOverlapSearchIndex++;
        }

        return returnValue;
    }

    /**
     * Return a list of intervals overlapping loc
     *
     * @param loc the location we want overlapping intervals
     * @return a non-null list of locations that overlap loc
     */
    public List<GenomeLoc> getOverlapping(final GenomeLoc loc) {
        // the max ensures that if loc would be the first element, that we start searching at the first element
        final int index = Collections.binarySearch(mArray, loc);
        if ( index >= 0 )
            // we can safely return a singleton because overlapping regions are merged and loc is exactly in
            // the set already
            return Collections.singletonList(loc);

        // if loc isn't in the list index is (-(insertion point) - 1). The insertion point is defined as the point at
        // which the key would be inserted into the list: the index of the first element greater than the key, or list.size()
        // -ins - 1 = index => -ins = index + 1 => ins = -(index + 1)
        // Note that we look one before the index in this case, as loc might occur after the previous overlapping interval
        final int start = Math.max(-(index + 1) - 1, 0);
        final int size = mArray.size();

        final List<GenomeLoc> overlapping = new LinkedList<>();
        for ( int i = start; i < size; i++ ) {
            final GenomeLoc myLoc = mArray.get(i);
            if ( loc.overlapsP(myLoc) )
                overlapping.add(myLoc);
            else if ( myLoc.isPast(loc) )
                // since mArray is ordered, if myLoc is past loc that means all future
                // intervals cannot overlap loc either.  So we can safely abort the search
                // note that we need to be a bit conservative on our tests since index needs to start
                // at -1 the position of index, so it's possible that myLoc and loc don't overlap but the next
                // position might
                break;
        }

        return overlapping;
    }

    /**
     * Return a list of intervals overlapping loc by enumerating all locs and testing for overlap
     *
     * Purely for testing purposes -- this is way to slow for any production code
     *
     * @param loc the location we want overlapping intervals
     * @return a non-null list of locations that overlap loc
     */
    protected List<GenomeLoc> getOverlappingFullSearch(final GenomeLoc loc) {
        final List<GenomeLoc> overlapping = new LinkedList<>();

        // super slow, but definitely works
        overlapping.addAll(mArray.stream().filter(loc::overlapsP).collect(Collectors.toList()));

        return overlapping;
    }

    /**
     * Adds a GenomeLoc to the collection, inserting at the correct sorted position into the set.
     * Throws an exception if the loc overlaps another loc already in the set.
     *
     * @param loc the GenomeLoc to add
     *
     * @return true if the loc was added or false otherwise (if the loc was null)
     */
    @Override
    public boolean add(final GenomeLoc loc) {
        return add(loc, MergeStrategy.DO_NOT_MERGE);
    }

    /**
     * Adds a GenomeLoc to the collection, merging it if it overlaps another region.
     * If it's not overlapping then we insert it at the correct sorted position into the set.
     *
     * @param loc the GenomeLoc to add
     *
     * @return true if the loc was added or false otherwise (if the loc was null)
     */
    public boolean addRegion(final GenomeLoc loc) {
        return add(loc, MergeStrategy.MERGE_OVERLAPS);
    }

    /**
     * An enum describing the different ways in which one may wish to merge GenomeLocs
     */
    public enum MergeStrategy {
        DO_NOT_MERGE, // nothing is merged
        MERGE_OVERLAPS, // overlapping (sharing a locus) GenomeLocs will be merged
        MERGE_CONTIGUOUS // overlapping _or_ just abutting GenomeLocs will be merged
    }

    /**
     * Adds a GenomeLoc to the collection, inserting at the correct sorted position into the set.
     *
     * @param loc           The GenomeLoc to add
     * @param mergeStrategy A merge strategy informing whether to avoid merging at all, to merge overlapping
     *                      intervals, or to merge contiguous (i.e. overlapping or abutting) intervals.
     *                      If mergeStrategy == DO_NOT_MERGE and loc overlaps another in the set, an exception is thrown
     *
     * @return true if the loc was added or false otherwise (if the loc was null or an exact duplicate)
     *
     * @throws IllegalArgumentException If loc overlaps any GenomeLoc in the set but mergeStrategy == DO_NOT_MERGE
     */
    public boolean add(final GenomeLoc loc, final MergeStrategy mergeStrategy) throws IllegalArgumentException {
        if ( loc == null )
            return false;

        // if we have no other intervals yet or if the new loc is past the last one in the list (which is usually the
        // case because locs are generally added in order) then be extra efficient and just add the loc to the end
        if ( mArray.size() == 0 || loc.isBeyond(mArray.get(mArray.size() - 1))) {
            return mArray.add(loc);
        }

        // find where in the list the new loc belongs
        final int binarySearchIndex = Collections.binarySearch(mArray, loc);

        // if it already exists in the list, return or throw an exception as needed
        if ( binarySearchIndex >= 0 ) {
            if ( mergeStrategy != MergeStrategy.DO_NOT_MERGE )
                return false;
            throw new IllegalArgumentException("GenomeLocSortedSet already contains the GenomeLoc " + loc);
        }

        // if it overlaps a loc already in the list merge or throw an exception as needed
        final int insertionIndex = -1 * (binarySearchIndex + 1);
        if ( ! mergeOverlappingIntervalsFromAdd(loc, insertionIndex, mergeStrategy) ) {
            // it does not overlap any current intervals, so add it to the set
            mArray.add(insertionIndex, loc);
        }

        return true;
    }



    /**
     * If the provided GenomeLoc overlaps another already in the set, merge them (or throw an exception if requested)
     *
     * @param loc                      the GenomeLoc to add
     * @param insertionIndex           the index in the sorted set to add the new loc
     * @param mergeStrategy            a MergeStrategy informing how to deal with overlapping and abutting intervals
     *
     * @return true if the loc was added or false otherwise
     */
    private boolean mergeOverlappingIntervalsFromAdd(final GenomeLoc loc, final int insertionIndex, final MergeStrategy mergeStrategy) {
        // try merging with the previous index
        if ( insertionIndex != 0 && loc.overlapsP(mArray.get(insertionIndex - 1)) ) {
            if ( mergeStrategy == MergeStrategy.DO_NOT_MERGE )
                throw new IllegalArgumentException(String.format("GenomeLocSortedSet contains a GenomeLoc (%s) that overlaps with the provided one (%s)", mArray.get(insertionIndex - 1).toString(), loc.toString()));
            mArray.set(insertionIndex - 1, mArray.get(insertionIndex - 1).merge(loc));
            return true;
        }

        // if asking to merge contiguous, and is contiguous, merge and add
        if ( mergeStrategy == MergeStrategy.MERGE_CONTIGUOUS & insertionIndex != 0 && loc.contiguousP(mArray.get(insertionIndex - 1)) ) {
            mArray.set(insertionIndex - 1, mArray.get(insertionIndex - 1).merge(loc));
            return true;
        }

        // try merging with the following index
        if ( insertionIndex < mArray.size() && loc.overlapsP(mArray.get(insertionIndex)) ) {
            if ( mergeStrategy == MergeStrategy.DO_NOT_MERGE )
                throw new IllegalArgumentException(String.format("GenomeLocSortedSet contains a GenomeLoc (%s) that overlaps with the provided one (%s)", mArray.get(insertionIndex).toString(), loc.toString()));
            mArray.set(insertionIndex, mArray.get(insertionIndex).merge(loc));
            return true;
        }

        // if asking to merge contiguous, and is contiguous, merge and add
        if ( mergeStrategy == MergeStrategy.MERGE_CONTIGUOUS && insertionIndex < mArray.size() && loc.contiguousP(mArray.get(insertionIndex)) ) {
            mArray.set(insertionIndex, mArray.get(insertionIndex).merge(loc));
            return true;
        }

        return false;
    }

    public GenomeLocSortedSet subtractRegions(GenomeLocSortedSet toRemoveSet) {
        LinkedList<GenomeLoc> good = new LinkedList<>();
        Stack<GenomeLoc> toProcess = new Stack<>();
        Stack<GenomeLoc> toExclude = new Stack<>();

        // initialize the stacks
        toProcess.addAll(mArray);
        Collections.reverse(toProcess);
        toExclude.addAll(toRemoveSet.mArray);
        Collections.reverse(toExclude);

        int i = 0;
        while ( ! toProcess.empty() ) {    // while there's still stuff to process
            if ( toExclude.empty() ) {
                good.addAll(toProcess);         // no more excludes, all the processing stuff is good
                break;
            }

            GenomeLoc p = toProcess.peek();
            GenomeLoc e = toExclude.peek();

            if ( p.overlapsP(e) ) {
                toProcess.pop();
                p.subtract(e).forEach(toProcess::push);
            } else if ( p.compareContigs(e) < 0 ) {
                good.add(toProcess.pop());         // p is now good
            } else if ( p.compareContigs(e) > 0 ) {
                toExclude.pop();                 // e can't effect anything
            } else if ( p.getStop() < e.getStart() ) {
                good.add(toProcess.pop());         // p stops before e starts, p is good
            } else if ( e.getStop() < p.getStart() ) {
                toExclude.pop();                 // p starts after e stops, e is done
            } else {
                throw new ReviewedGATKException("BUG: unexpected condition: p=" + p + ", e=" + e);
            }

            if ( i++ % 10000 == 0 )
                logger.debug("removeRegions operation: i = " + i);
        }

        return createSetFromList(genomeLocParser,good);
    }

    /**
     * a simple removal of an interval contained in this list.  The interval must be identical to one in the list (no partial locations or overlapping)
     * @param location the GenomeLoc to remove
     */
    public void remove(GenomeLoc location) {
        if (!mArray.contains(location)) throw new IllegalArgumentException("Unable to remove location: " + location + ", not in the list");
        mArray.remove(location);
    }

    /**
     * create a list of genomic locations, given a reference sequence
     *
     * @param dict the sequence dictionary to create a collection from
     *
     * @return the GenomeLocSet of all references sequences as GenomeLoc's
     */
    public static GenomeLocSortedSet createSetFromSequenceDictionary(final SAMSequenceDictionary dict) {
        final GenomeLocParser parser = new GenomeLocParser(dict);
        final GenomeLocSortedSet returnSortedSet = new GenomeLocSortedSet(parser);
        returnSortedSet.addAll(dict.getSequences().stream().map(sequence -> parser.createOverEntireContig(sequence.getSequenceName())).collect(Collectors.toList()));
        return returnSortedSet;
    }

    /**
     * Create a sorted genome location set from a list of GenomeLocs.
     *
     * @param locs the list<GenomeLoc>
     *
     * @return the sorted genome loc list
     */
    public static GenomeLocSortedSet createSetFromList(final GenomeLocParser parser, final List<GenomeLoc> locs) {
        GenomeLocSortedSet set = new GenomeLocSortedSet(parser);
        set.addAll(locs);
        return set;
    }

    /**
     * return a deep copy of this collection.
     *
     * @return a new GenomeLocSortedSet, identical to the current GenomeLocSortedSet.
     */
    public GenomeLocSortedSet clone() {
        GenomeLocSortedSet ret = new GenomeLocSortedSet(genomeLocParser);
        // ensure a deep copy
        ret.mArray.addAll(this.mArray.stream().map(loc -> genomeLocParser.createGenomeLoc(loc.getContig(), loc.getStart(), loc.getStop())).collect(Collectors.toList()));
        return ret;
    }

    /**
     * convert this object to a list
     * @return the lists
     */
    public List<GenomeLoc> toList() {
        return this.mArray;
    }

    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("[");
        for ( GenomeLoc e : this ) {
            s.append(" ");
            s.append(e.toString());
        }
        s.append("]");

        return s.toString();
    }
}
