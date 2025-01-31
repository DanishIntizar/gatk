package org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.tools.walkers.annotator.StrandOddsRatio;
import org.broadinstitute.hellbender.utils.genotyper.AlleleLikelihoods;
import org.broadinstitute.hellbender.utils.help.HelpConstants;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Allele-specific strand bias estimated by the Symmetric Odds Ratio test
 *
 * <p>Strand bias is a type of sequencing bias in which one DNA strand is favored over the other, which can result in
 * incorrect evaluation of the amount of evidence observed for one allele vs. the other. The AS_StrandOddsRatio
 * annotation is one of several methods that aims to evaluate whether there is strand bias in the data. It is an
 * updated form of the Fisher Strand Test that is better at taking into account large amounts of data in high coverage
 * situations. It is used to determine if there is strand bias between forward and reverse strands for the reference or
 * alternate allele. It does so separately for each allele. The reported value is ln-scaled.</p>
 *
 * <h3>Statistical notes</h3>
 * <p>The following 2x2 contingency table gives the notation for allele support and strand orientation.</p>
 *
 * <table>
 * <tr><th>&nbsp;</th><th>+ strand&nbsp;&nbsp;&nbsp;</th><th>- strand&nbsp;&nbsp;&nbsp;</th></tr>
 * <tr><th>REF&nbsp;&nbsp;&nbsp;</th><td>X[0][0]</td><td>X[0][1]</td></tr>
 * <tr><th>ALT&nbsp;&nbsp;&nbsp;</th><td>X[1][0]</td><td>X[1][1]</td></tr>
 * </table>
 *
 * <p>We can then represent the Odds Ratios with the equation:</p>
 *
 * <img src="http://latex.codecogs.com/svg.latex?R = \frac{X[0][0] * X[1][1]}{X[0][1] * X[1][0]}" border="0"/>
 *
 * <p>and its inverse:</p>
 *
 * <img src="http://latex.codecogs.com/svg.latex?\frac{1}{R} = \frac{X[0][1] * X[1][0]}{X[0][0] * X[1][1]}" border="0"/>
 *
 * <p>The sum R + 1/R is used to detect a difference in strand bias for REF and for ALT. The sum makes it symmetric.
 * A high value is indicative of large difference where one entry is very small compared to the others. A scale factor
 * of refRatio/altRatio where</p>
 *
 * <img src="http://latex.codecogs.com/svg.latex?refRatio = \frac{min(X[0][0], X[0][1])}{max(X[0][0], X[0][1])}" border="0"/>
 *
 * <p>and </p>
 *
 * <img src="http://latex.codecogs.com/svg.latex?altRatio = \frac{min(X[1][0], X[1][1])}{max(X[1][0], X[1][1])}" border="0"/>
 *
 * <p>ensures that the annotation value is large only. The final SOR annotation is given in natural log space.</p>
 *
 * <p>See the <a href="http://www.broadinstitute.org/gatk/guide/article?id=4732">method document on statistical tests</a>
 * for a more detailed explanation of this statistical test, and see
 * <a href="https://software.broadinstitute.org/gatk/documentation/tooldocs/current/org_broadinstitute_hellbender_tools_walkers_annotator_StrandOddsRatio.php">StrandOddsRatio</a>
 * documentation for an example calculation.</p>
 *
 * <h3>Caveat</h3>
 * <p>
 * The name AS_StrandOddsRatio is not entirely appropriate because the implementation was changed somewhere between the start of development and release of this annotation. Now SOR isn't really an odds ratio anymore. The goal was to separate certain cases of data without penalizing variants that occur at the ends of exons because they tend to only be covered by reads in one direction (depending on which end of the exon they're on), so if a variant has 10 ref reads in the + direction, 1 ref read in the - direction, 9 alt reads in the + direction and 2 alt reads in the - direction, it's actually not strand biased, but the FS score is pretty bad. The implementation that resulted derived in part from empirically testing some read count tables of various sizes with various ratios and deciding from there.</p>
 *
 * <h3>Related annotations</h3>
 * <ul>
 *     <li><b>AS_FisherStrand</b> uses Fisher's Exact Test to evaluate strand bias.</li>
 *     <li><b>StrandOddsRatio</b> outputs a version of this annotation that includes all alternate alleles in a single calculation.</li>
 *     <li><b>StrandBiasBySample</b> outputs counts of read depth per allele for each strand orientation.</li>
 *
 * </ul>
 *
 */
@DocumentedFeature(groupName=HelpConstants.DOC_CAT_ANNOTATORS, groupSummary=HelpConstants.DOC_CAT_ANNOTATORS_SUMMARY, summary="Allele-specific strand bias estimated by the symmetric odds ratio test (AS_SOR)")
public final class AS_StrandOddsRatio extends AS_StrandBiasTest implements AS_StandardAnnotation {

    @Override
    public List<String> getKeyNames() {
        return Collections.singletonList(GATKVCFConstants.AS_STRAND_ODDS_RATIO_KEY);
    }

    @Override
    protected Map<String, Object> calculateAnnotationFromLikelihoods(final AlleleLikelihoods<GATKRead, Allele> likelihoods,
                                                                     final VariantContext vc){
        // either SNP with no alignment context, or indels: per-read likelihood map needed
        final int[][] table = getContingencyTable(likelihoods, vc, MIN_COUNT);
        final double ratio = StrandOddsRatio.calculateSOR(table);
        return Collections.singletonMap(getKeyNames().get(0), StrandOddsRatio.formattedValue(ratio));
    }

    /**
     *
     * @param combinedData
     * @return a map with alternate alleles as keys and may contain null values
     */
    @Override
    protected Map<Allele,Double> calculateReducedData(AlleleSpecificAnnotationData<List<Integer>> combinedData) {
        final Map<Allele,Double> annotationMap = new HashMap<>();
        final Map<Allele, List<Integer>> perAlleleData = combinedData.getAttributeMap();
        final List<Integer> refStrandCounts = perAlleleData.get(combinedData.getRefAllele());
        for (final Allele a : perAlleleData.keySet()) {
            List<Integer> altStrandCounts = perAlleleData.get(a);
            if (altStrandCounts.isEmpty()) {
                annotationMap.put(a, null);
            } else {
                int[][] refAltTable = new int[][]{new int[]{refStrandCounts.get(FORWARD), refStrandCounts.get(REVERSE)},
                        new int[]{altStrandCounts.get(FORWARD), altStrandCounts.get(REVERSE)}};
                annotationMap.put(a, StrandOddsRatio.calculateSOR(refAltTable));
            }
        }
        return annotationMap;
    }
}
