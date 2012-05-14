package org.broadinstitute.sting.gatk.walkers.genotyper;

import net.sf.picard.reference.IndexedFastaSequenceFile;
import net.sf.samtools.CigarOperator;
import net.sf.samtools.SAMFileHeader;
import org.apache.commons.lang.ArrayUtils;
import org.broadinstitute.sting.commandline.*;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.filters.DuplicateReadFilter;
import org.broadinstitute.sting.gatk.filters.FailsVendorQualityCheckFilter;
import org.broadinstitute.sting.gatk.filters.MappingQualityZeroFilter;
import org.broadinstitute.sting.gatk.refdata.ReadMetaDataTracker;
import org.broadinstitute.sting.gatk.refdata.utils.GATKFeature;
import org.broadinstitute.sting.gatk.report.*;
import org.broadinstitute.sting.gatk.walkers.ReadFilters;
import org.broadinstitute.sting.gatk.walkers.ReadWalker;
import org.broadinstitute.sting.utils.*;
import org.broadinstitute.sting.utils.clipping.ReadClipper;
import org.broadinstitute.sting.utils.codecs.refseq.RefSeqFeature;
import org.broadinstitute.sting.utils.codecs.table.TableFeature;
import org.broadinstitute.sting.utils.codecs.vcf.VCFConstants;
import org.broadinstitute.sting.utils.codecs.vcf.VCFHeader;
import org.broadinstitute.sting.utils.codecs.vcf.VCFHeaderLine;
import org.broadinstitute.sting.utils.collections.Pair;
import org.broadinstitute.sting.utils.exceptions.StingException;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.fasta.CachingIndexedFastaSequenceFile;
import org.broadinstitute.sting.utils.sam.AlignmentUtils;
import org.broadinstitute.sting.utils.sam.GATKSAMRecord;
import org.broadinstitute.sting.utils.text.XReadLines;
import org.broadinstitute.sting.utils.variantcontext.*;
import org.broadinstitute.sting.utils.variantcontext.writer.VariantContextWriter;
import org.broadinstitute.sting.utils.variantcontext.writer.VariantContextWriterFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: chartl
 * Date: 11/16/11
 * Time: 5:09 PM
 * To change this template use File | Settings | File Templates.
 */
@ReadFilters({DuplicateReadFilter.class,FailsVendorQualityCheckFilter.class,MappingQualityZeroFilter.class})
public class ExonJunctionGenotyper extends ReadWalker<ExonJunctionGenotyper.EvaluationContext,ExonJunctionGenotyper.ECLikelihoods> {


    /**
     * A raw, unfiltered, highly specific callset in VCF format.
     */
    @Output(doc="File to which variants should be written", required = true)
    protected VariantContextWriter vcfWriterBase = null;

    protected VariantContextWriter vcfWriter;

    @Input(shortName="r",fullName="refSeq",required=true,doc="The RefSeq Gene definition track")
    public RodBinding<RefSeqFeature> refSeqRodBinding;

    @Input(shortName="H",fullName="insertHistogram",required=true,doc="The insert size histogram per read group, either flat file or GATK report formatted. One or more of these can be a .list file containing paths to multiple of such histogram files.")
    public List<File> readGroupInsertHistogram;

    @Input(shortName="Y",fullName="hypothesis",required=true,doc="The set of gene(transcript) hypothesis generated by the Hypothesis Generator that we want to score")
    public RodBinding<TableFeature> hypothesisRodBinding;

    @Argument(shortName="FSW", fullName="fullSmithWaterman",doc="Run SmithWaterman of every exon-intron overlapping read against exon-exon junction. Defaults to doing this only for unmapped pairs of mapped read.",required=false)
    public boolean fullSmithWaterman = false;

    @Hidden
    @Input(fullName="maxInsertSize",required=false)
    public int MAX_INSERT_SIZE = 1000;

    public final static byte MIN_TAIL_QUALITY = 6;

    private Map<String,byte[]> insertQualsByRG = new HashMap<String,byte[]>();

    private boolean initialized = false;
    private IndexedFastaSequenceFile referenceReader;
    private Set<String> samples;
    private List<Allele> noCallAlleles = Arrays.asList(new Allele[]{Allele.NO_CALL,Allele.NO_CALL});
    private Map<String,JunctionHypothesis> activeHypotheses;

    // the likelihoods engine
    IntronLossGenotypeLikelihoodCalculationModel ilglcm= new IntronLossGenotypeLikelihoodCalculationModel(logger,fullSmithWaterman);

    public ECLikelihoods reduceInit() {
        return new ECLikelihoods(ilglcm);
    }

    public ECLikelihoods reduce(EvaluationContext context, ECLikelihoods prevRed) {
        if ( context != null && context.read.getReadGroup() != null ) {
            logger.debug(String.format("%s   %s",prevRed.getLocation(), getToolkit().getGenomeLocParser().createGenomeLoc(context.read)));
            if ( prevRed.getLocation() != null && (context.read.getReferenceIndex() > prevRed.getLocation().getContigIndex() || context.read.getUnclippedStart() > prevRed.getLocation().getStop()) ) {
                printToFile(prevRed);
                prevRed.purge();
                activeHypotheses.clear();
            }
            prevRed.update(context);
        }

        return prevRed;
    }

    public EvaluationContext map(ReferenceContext context, GATKSAMRecord read, ReadMetaDataTracker metaDataTracker) {
        if ( read.getReadGroup() == null ) { return null; }
        HashSet<TableFeature> hypotheses = new HashSet<TableFeature>(16);
        for (GATKFeature feature : metaDataTracker.getAllCoveringRods() ) {
            if ( feature.getUnderlyingObject().getClass().isAssignableFrom(TableFeature.class) ) {
                hypotheses.add((TableFeature) feature.getUnderlyingObject());
            }
        }
        //logger.debug("Tracker Size "+Integer.toString(metaDataTracker.getAllCoveringRods().size())+" Hyp: "+hypotheses.size());

        if ( hypotheses.size() == 0 ) {
            return null;
        }

        //logger.debug(hypotheses);

        Map<String,RefSeqFeature> refSeqFeatures = new HashMap<String,RefSeqFeature>(16);
        GenomeLoc readLoc = null;
        if ( ! read.getReadUnmappedFlag() ) {
            readLoc =  getToolkit().getGenomeLocParser().createGenomeLoc(read);
        } else if ( ! read.getMateUnmappedFlag() ) {
            readLoc = getToolkit().getGenomeLocParser().createGenomeLoc(read.getMateReferenceName(),read.getMateAlignmentStart(),read.getMateAlignmentStart()+read.getReadLength());
        }

        if (readLoc == null ) {
            return null;
        }

        for (GATKFeature feature : metaDataTracker.getAllCoveringRods() ) {
            if ( feature.getUnderlyingObject().getClass().isAssignableFrom(RefSeqFeature.class) ) {
                refSeqFeatures.put(((RefSeqFeature) feature.getUnderlyingObject()).getTranscriptUniqueGeneName(),(RefSeqFeature) feature.getUnderlyingObject());
            }
        }

        EvaluationContext ec = new EvaluationContext();

        if ( ! read.getReadUnmappedFlag() ) {
            int start = read.getUnclippedStart();
            int stop= read.getUnclippedEnd();
            int leftHardClipAlready = read.getCigar().getCigarElement(0).getOperator().equals(CigarOperator.H) ? read.getCigar().getCigarElement(0).getLength() : 0;
            byte[] refBases = getToolkit().getReferenceDataSource().getReference().getSubsequenceAt(read.getReferenceName(),start, stop).getBases();
            GATKSAMRecord initRead = ReadClipper.hardClipLowQualEnds(read, (byte) 12);
            if ( initRead.getCigarString() == null ) {
                return null;
            }
            GATKSAMRecord clippedRead = ReadClipper.revertSoftClippedBases(initRead);

            if( clippedRead.getCigar().isEmpty()) {
                return null;
            }
            int offset = clippedRead.getCigar().getCigarElement(0).getOperator().equals(CigarOperator.H) ? clippedRead.getCigar().getCigarElement(0).getLength() - leftHardClipAlready : 0;
            clippedRead.setAttribute("NM", AlignmentUtils.getMismatchCount(clippedRead, refBases, offset).numMismatches);

            ec.read = clippedRead;
        } else {
            ec.read = ReadClipper.hardClipLowQualEnds(read, (byte) 12);
        }

        ec.refSeqByName = refSeqFeatures;
        // todo -- this is wildly inefficient, why regenerate hypotheses for every read?! just store in a map.
        ec.hypotheses = new TreeSet<JunctionHypothesis>();
        for ( TableFeature f : hypotheses ) {
            if ( activeHypotheses.containsKey(f.getValue(1))) {
                ec.hypotheses.add(activeHypotheses.get(f.getValue(1)));
            } else {
                RefSeqFeature fe = refSeqFeatures.get(f.getValue(1));
                if ( fe == null ) {
                    // cannot form a hypothesis without a ref seq feature
                    continue;
                }
                try {
                    for ( List<Pair<Integer,Integer>> junc : JunctionHypothesis.unwrapPairs(f.getValue(3))) {
                        if ( junc.size() > 0 ) {
                            JunctionHypothesis jc = new JunctionHypothesis(f.getValue(1),fe,junc,referenceReader);
                            ec.hypotheses.add(jc);
                            activeHypotheses.put(f.getValue(1),jc);
                        }
                    }
                } catch (IllegalArgumentException  e ) {
                    // just telling us that there were disparate events that could not be consolidated into a retrogene
                    continue;
                }
            }
        }

        return ec;
    }

    @Override
    public void initialize() {

        Set<String> sampleStr = SampleUtils.getSAMFileSamples(getToolkit());
        ilglcm.setSamples(sampleStr);

        vcfWriter = VariantContextWriterFactory.sortOnTheFly(vcfWriterBase,1500000);
        vcfWriter.writeHeader(new VCFHeader(new HashSet<VCFHeaderLine>(), sampleStr));

        try {
            for ( File readGroupInsertHistogramFile : readGroupInsertHistogram ) {
                logger.debug("Reading: "+readGroupInsertHistogramFile.getAbsolutePath());
                if ( readGroupInsertHistogramFile.getAbsolutePath().endsWith(".list")) {
                    // this is a list of histograms, read each one separately
                    for ( String line : new XReadLines(readGroupInsertHistogramFile) ) {
                        logger.debug("Reading "+line+" within "+readGroupInsertHistogramFile.getAbsolutePath());
                        readHistoFile(new File(line));
                    }
                } else {
                    readHistoFile(readGroupInsertHistogramFile);
                }
            }
        } catch (FileNotFoundException e) {
            throw new UserException("Histogram file not found "+e.getMessage(),e);
        } catch (IOException e) {
            throw new StingException("IO Exception",e);
        }

        StringBuffer debug = new StringBuffer();
        for ( Map.Entry<String,byte[]> etry : insertQualsByRG.entrySet() ) {
            debug.append("  ");
            debug.append(etry.getKey());
            debug.append("->");
            debug.append(Arrays.deepToString(ArrayUtils.toObject(etry.getValue())));
        }

        logger.debug(debug);

        ilglcm.initialize(refSeqRodBinding,getToolkit().getGenomeLocParser(),insertQualsByRG);

        try {
            // fasta reference reader to supplement the edges of the reference sequence
            referenceReader = new CachingIndexedFastaSequenceFile(getToolkit().getArguments().referenceFile);
        }
        catch(FileNotFoundException ex) {
            throw new UserException.CouldNotReadInputFile(getToolkit().getArguments().referenceFile,ex);
        }

        samples = new HashSet<String>(128);
        for (SAMFileHeader h : getToolkit().getSAMFileHeaders() ) {
            samples.addAll(SampleUtils.getSAMFileSamples(h));
        }

        activeHypotheses = new HashMap<String,JunctionHypothesis>();
    }

    private void readHistoFile(File readGroupInsertHistogramFile ) throws FileNotFoundException, IOException{ // exceptions handled upstream
        XReadLines xrl = new XReadLines(readGroupInsertHistogramFile);
        if ( ! xrl.hasNext() ) {
            logger.warn("The histogram file appears to be empty: "+readGroupInsertHistogramFile.getAbsolutePath());
            return;
        }
        if ( ! xrl.next().startsWith("##:") ) {
            xrl.close();
            for ( String entry : new XReadLines(readGroupInsertHistogramFile) ) {
                String[] split1 = entry.split("\\t");
                String id = split1[0];
                String[] histogram = split1[1].split(";");
                byte[] quals = new byte[histogram.length];
                int idx = 0;
                for ( String histEntry : histogram ) {
                    try {
                        quals[idx++] = Byte.parseByte(histEntry);
                    } catch( NumberFormatException e) {
                        quals[idx-1] = QualityUtils.probToQual(Double.parseDouble(histEntry));
                    }
                }

                insertQualsByRG.put(id,quals);
            }
        } else {
            xrl.close();
            GATKReport report = new GATKReport(readGroupInsertHistogramFile);
            GATKReportTable reportTable = report.getTable("InsertSizeDistributionByReadGroup");
            // rows are insert sizes, columns are read groups
            for (GATKReportColumn reportColumn : reportTable.getColumnInfo() ) {
                // annoyingly, the column has no knowledge of its own rows
                int sum = 0;
                for ( int row = 0; row < reportTable.getNumRows(); row++ ) {
                    sum += Integer.parseInt( (String) reportTable.get(row,reportColumn.getColumnName()));
                }
                byte[] rgHist = new byte[MAX_INSERT_SIZE];
                for ( int row = 0; row < reportTable.getNumRows(); row++ ) {
                    final int insertSize = Integer.parseInt( (String) reportTable.get(row,0));
                    int val = 1;
                    if ( insertSize < rgHist.length ) {
                        val = Integer.parseInt( (String) reportTable.get(row,reportColumn.getColumnName()));
                    }
                    rgHist[row] = QualityUtils.probToQual( 1.0-( ( (double) val )/sum ), Math.pow(10,-25.4) );
                }

                insertQualsByRG.put(reportColumn.getColumnName(),rgHist);
            }
        }
    }

    public void onTraversalDone(ECLikelihoods likelihoods) {
        printToFile(likelihoods);
        vcfWriter.close();
    }

    private void printToFile(ECLikelihoods likelihoods) {
        if ( likelihoods.context == null ) {
            return;
        }
        logger.info("HYPO_SIZE: "+Integer.toString(likelihoods.context.hypotheses.size()));
        for ( JunctionHypothesis hypothesis : likelihoods.context.hypotheses ) {
            Map<String,double[]> rawLiks = likelihoods.likelihoods.get(hypothesis);
            if ( rawLiks.size() == 0 ) {
                continue;
            }
            Allele alt = Allele.create(hypothesis.toString(),false);
            GenomeLoc refPos = hypothesis.getLocation().getStartLocation();
            //logger.info(hypothesis.getLocation());
            Allele ref = Allele.create(referenceReader.getSubsequenceAt(refPos.getContig(), refPos.getStart(), refPos.getStop()).getBases(), true);
            Byte paddingBase = referenceReader.getSubsequenceAt(refPos.getContig(),refPos.getStart()-1,refPos.getStart()-1).getBases()[0];
            GenotypesContext GLs = GenotypesContext.create(samples.size());
            final List<Allele> noCall = new ArrayList<Allele>();
            noCall.add(Allele.NO_CALL);
            for ( String s : samples ) {
                //logger.info(gl.getKey() + Arrays.deepToString(ArrayUtils.toObject(gl.getValue())));
                HashMap<String, Object> attributes = new HashMap<String, Object>();
                if ( rawLiks.containsKey(s) ) {
                    attributes.put(VCFConstants.PHRED_GENOTYPE_LIKELIHOODS_KEY, GenotypeLikelihoods.fromLog10Likelihoods(MathUtils.normalizeFromLog10(rawLiks.get(s), false, true)));
                } else {
                    attributes.put(VCFConstants.PHRED_GENOTYPE_LIKELIHOODS_KEY,VCFConstants.MISSING_VALUE_v4);
                }
                GLs.add(new Genotype(s, noCall, Genotype.NO_LOG10_PERROR, null, attributes, false));
            }
            Map<String,Object> attributes = new HashMap<String,Object>();
            attributes.put("SNS", likelihoods.getSupNoSupRatio());
            // see if we need to warn on these scores
            if ( hypothesis.getScoreDifference() > 5 ) {
                logger.warn("There is some evidence suggesting an insertion or deletion polymorphism on retrotranscript for " +
                     "hypothesis "+hypothesis.toString()+" . Would suggest running with full smith waterman to avoid misgenotyping");
                attributes.put("FSWF",true);
            }
            AlleleFrequencyCalculationResult result = new AlleleFrequencyCalculationResult(1);
            double[] prior = computeAlleleFrequencyPriors(GLs.size()*2+1);
            // gls, num alt, priors, result, preserve
            ExactAFCalculationModel.linearExactMultiAllelic(GLs,1,prior,result);
            VariantContextBuilder vcb = new VariantContextBuilder("EJG",refPos.getContig(),refPos.getStop(),refPos.getStop(),Arrays.asList(ref,alt));
            vcb.genotypes(GLs);
            vcb.referenceBaseForIndel(paddingBase);
            List<Allele> alleles = new ArrayList<Allele>(2);
            alleles.add(ref);
            alleles.add(Allele.create(hypothesis.toString()));
            vcb.alleles(alleles);
            VariantContext asCon = vcb.make();
            GenotypesContext genAssigned = VariantContextUtils.assignDiploidGenotypes(asCon);
            vcb.genotypes(genAssigned);
            final double[] normalizedPosteriors = UnifiedGenotyperEngine.generateNormalizedPosteriors(result, new double[2]);
            logger.debug(normalizedPosteriors[0]);
            double log10err;
            if ( Double.isInfinite(normalizedPosteriors[0]) ) {
                log10err = result.getLog10LikelihoodOfAFzero();
            } else {
                log10err = normalizedPosteriors[0];
            }
            vcb.log10PError(log10err);
            attributes.put("MLEAC",result.getAlleleCountsOfMLE()[0]);
            VariantContextUtils.calculateChromosomeCounts(vcb.make(),attributes,false);
            vcb.attributes(attributes);
            vcfWriter.add(vcb.make());
        }

    }

    private double[] computeAlleleFrequencyPriors(int N) {
        // todo -- this stolen from the UG, maybe call into it?
        // calculate the allele frequency priors for 1-N
        double sum = 0.0;
        double heterozygosity = 0.0000001; // ~ 10 differences per person = 10/3gb ~ 1/100 mil
        double[] priors = new double[2*N+1];

        for (int i = 1; i <= 2*N; i++) {
            double value = heterozygosity / (double)i;
            priors[i] = Math.log10(value);
            sum += value;
        }

        // null frequency for AF=0 is (1 - sum(all other frequencies))
        priors[0] = Math.log10(1.0 - sum);
        return priors;
    }

    class EvaluationContext  {
        public GATKSAMRecord read;
        public Map<String,RefSeqFeature> refSeqByName;
        public TreeSet<JunctionHypothesis> hypotheses;

        public void merge(EvaluationContext that) {
            hypotheses.addAll(that.hypotheses);
            refSeqByName.putAll(that.refSeqByName);
            read = that.read;
        }

        public boolean malformed() {
            // reads cam come after the hypotheses they're built from. It's dumb, but a property of the tracker.
            return hypotheses.size() == 0 || ! getToolkit().getGenomeLocParser().createGenomeLoc(read).overlapsP(hypotheses.first().getLocation().getStartLocation().endpointSpan(hypotheses.last().getLocation().getStopLocation()));
        }
    }

    class ECLikelihoods implements HasGenomeLocation {
        public Map<JunctionHypothesis,Map<String,double[]>> likelihoods = new TreeMap<JunctionHypothesis,Map<String,double[]>>();
        public EvaluationContext context;
        private IntronLossGenotypeLikelihoodCalculationModel ilglcm;
        GenomeLoc latestEnd = null;
        private int sup;
        private int noSup;

        public ECLikelihoods(IntronLossGenotypeLikelihoodCalculationModel model) {
            ilglcm = model;
            sup = 0;
            noSup = 0;
        }

        public void purge() {
            likelihoods = new TreeMap<JunctionHypothesis,Map<String,double[]>>();
            context = null;
        }

        public void update(EvaluationContext ec) {
            if ( ! ec.malformed() ) {
                if ( context == null ) {
                    context = ec;
                } else {
                    context.merge(ec);
                }

                if ( context != null && context.hypotheses.size() > likelihoods.size() ) {
                    for ( JunctionHypothesis j : ec.hypotheses ) {
                        GenomeLoc end = j.getLocation().getStopLocation();
                        if ( latestEnd == null || end.isPast(latestEnd) ) {
                            latestEnd = end;
                        }
                    }
                }

                updateLilkelihoods();
            }
        }

        private void updateLilkelihoods() {
            for ( JunctionHypothesis hypothesis : context.hypotheses ) {
                if ( ! likelihoods.containsKey(hypothesis) ) {
                    likelihoods.put(hypothesis,new HashMap<String,double[]>());
                }
                // early return if the read is off the edge of the hypothesized sequence
                boolean offEdge = context.read.getUnclippedStart() < hypothesis.getLocation().getStart() ||
                        context.read.getUnclippedEnd() > hypothesis.getLocation().getStop();
                if ( offEdge ) {
                    continue;
                }
                Pair<String,double[]> lik = ilglcm.getLikelihoods(hypothesis,context.read);
                if ( ! likelihoods.get(hypothesis).containsKey(lik.first) ) {
                    likelihoods.get(hypothesis).put(lik.first,new double[3]);
                }
                //logger.debug("Updating likelihoods for sample "+context.read.getReadGroup().getSample());
                updateLikelihoods(likelihoods.get(hypothesis).get(lik.first), lik.second);
                double[] dlik = likelihoods.get(hypothesis).get(lik.first);
                //logger.debug(String.format("[0] :: %f   [1] :: %f   [2] :: %f",dlik[0],dlik[1],dlik[2]));
            }
            //logger.info(String.format("Context: %d Likelihoods: %d",context.hypotheses.size(),likelihoods.size()));
        }

        private void updateLikelihoods(double[] sum, double[] newLik) {
            double max = Integer.MIN_VALUE;
            int maxIdx = -1;
            for ( int i = 0; i <= 2; i++ ) {
                if ( newLik[i] > max ) {
                    max = newLik[i];
                    maxIdx = i;
                }
                sum[i] += newLik[i];
            }
            if (MathUtils.compareDoubles(newLik[0],newLik[1],1e-5) != 0 ) {
                if ( maxIdx > 0 ) {
                    sup++;
                } else {
                    noSup++;
                }
            }
        }

        public GenomeLoc getLocation() {
            TreeSet<JunctionHypothesis> hypotheses = new TreeSet<JunctionHypothesis>(likelihoods.keySet());
            if ( hypotheses.size() == 0 ) {
                return null;
            }
            return hypotheses.first().getLocation().getStartLocation().endpointSpan(latestEnd);
        }

        public double getSupNoSupRatio() {
            return (0.0+sup)/(noSup);
        }
    }
}
