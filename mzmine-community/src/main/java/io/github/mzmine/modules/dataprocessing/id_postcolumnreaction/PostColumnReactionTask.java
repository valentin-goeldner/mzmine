/*
 * Copyright (c) 2004-2025 The mzmine Development Team
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

package io.github.mzmine.modules.dataprocessing.id_postcolumnreaction;

import static io.github.mzmine.modules.dataprocessing.id_formulapredictionfeaturelist.FormulaPredictionFeatureListParameters.elementalRatios;
import static io.github.mzmine.modules.dataprocessing.id_formulapredictionfeaturelist.FormulaPredictionFeatureListParameters.elements;
import static io.github.mzmine.modules.dataprocessing.id_formulapredictionfeaturelist.FormulaPredictionFeatureListParameters.ionization;
import static io.github.mzmine.modules.dataprocessing.id_formulapredictionfeaturelist.FormulaPredictionFeatureListParameters.isotopeFilter;
import static io.github.mzmine.modules.dataprocessing.id_formulapredictionfeaturelist.FormulaPredictionFeatureListParameters.msmsFilter;
import static io.github.mzmine.modules.dataprocessing.id_formulapredictionfeaturelist.FormulaPredictionFeatureListParameters.mzTolerance;
import static io.github.mzmine.modules.dataprocessing.id_formulapredictionfeaturelist.FormulaPredictionFeatureListParameters.rdbeRestrictions;
import static io.github.mzmine.modules.dataprocessing.id_postcolumnreaction.PostColumnReactionFormulaPredictionParameters.predElementalRatios;
import static io.github.mzmine.modules.dataprocessing.id_postcolumnreaction.PostColumnReactionFormulaPredictionParameters.predIonization;
import static io.github.mzmine.modules.dataprocessing.id_postcolumnreaction.PostColumnReactionFormulaPredictionParameters.predIsotopeFilter;
import static io.github.mzmine.modules.dataprocessing.id_postcolumnreaction.PostColumnReactionFormulaPredictionParameters.predMZTolerance;
import static io.github.mzmine.modules.dataprocessing.id_postcolumnreaction.PostColumnReactionFormulaPredictionParameters.predMsmsFilter;
import static io.github.mzmine.modules.dataprocessing.id_postcolumnreaction.PostColumnReactionFormulaPredictionParameters.predRdbeRestrictions;

import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.datamodel.features.FeatureList;
import io.github.mzmine.datamodel.features.FeatureListRow;
import io.github.mzmine.datamodel.features.compoundannotations.CompoundDBAnnotation;
import io.github.mzmine.datamodel.features.compoundannotations.SimpleCompoundDBAnnotation;
import io.github.mzmine.datamodel.features.correlation.R2RMap;
import io.github.mzmine.datamodel.features.correlation.RowsRelationship;
import io.github.mzmine.datamodel.features.types.annotations.CompoundNameType;
import io.github.mzmine.datamodel.features.types.numbers.PrecursorMZType;
import io.github.mzmine.main.MZmineCore;
import io.github.mzmine.modules.dataprocessing.id_formulaprediction.ResultFormula;
import io.github.mzmine.modules.dataprocessing.id_formulaprediction.restrictions.elements.ElementalHeuristicParameters;
import io.github.mzmine.modules.dataprocessing.id_formulaprediction.restrictions.rdbe.RDBERestrictionParameters;
import io.github.mzmine.modules.dataprocessing.id_formulapredictionfeaturelist.FormulaPredictionFeatureListParameters;
import io.github.mzmine.modules.dataprocessing.id_formulapredictionfeaturelist.FormulaPredictionFeatureListTask;
import io.github.mzmine.modules.dataprocessing.id_online_reactivity.OnlineLcReactivityModule;
import io.github.mzmine.modules.dataprocessing.id_online_reactivity.OnlineLcReactivityTask;
import io.github.mzmine.modules.tools.isotopepatternscore.IsotopePatternScoreParameters;
import io.github.mzmine.modules.tools.msmsscore.MSMSScoreParameters;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.parameters.parametertypes.selectors.RawDataFilesSelection;
import io.github.mzmine.taskcontrol.AbstractFeatureListTask;
import io.github.mzmine.taskcontrol.TaskStatus;
import io.github.mzmine.util.FeatureListRowSorter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.config.IsotopeFactory;
import org.openscience.cdk.config.Isotopes;
import org.openscience.cdk.formula.MolecularFormulaRange;
import org.openscience.cdk.interfaces.IIsotope;
import org.openscience.cdk.interfaces.IMolecularFormula;
import org.openscience.cdk.tools.manipulator.MolecularFormulaManipulator;

public class PostColumnReactionTask extends AbstractFeatureListTask {

  private static final Logger logger = Logger.getLogger(OnlineLcReactivityTask.class.getName());
  private final FeatureList flist;
  private final String description;
  private final Map<String, Integer> annotationCounts = new HashMap<>();
  private final List<RawDataFile> unreactedRaws;
  private final double corrThreshold;
  private final boolean checkFormulaPred;
  private FormulaPredictionFeatureListParameters predParamSet;


  public PostColumnReactionTask(@NotNull ParameterSet parameters, @NotNull Instant moduleCallDate) {
    super(null, moduleCallDate, parameters, OnlineLcReactivityModule.class);

    //Define feature list for processing
    this.flist = parameters.getParameter(PostColumnReactionParameters.flist).getValue()
        .getMatchingFeatureLists()[0];

    //Define unreacted raw datafiles
    RawDataFilesSelection unreactedSelection = parameters.getParameter(
        PostColumnReactionParameters.unreactedRawDataFiles).getValue();
    this.unreactedRaws = List.of(unreactedSelection.getMatchingRawDataFiles().clone());

    //Check for shape correlation threshold
    boolean checkCorrThreshold = parameters.getParameter(
        PostColumnReactionParameters.correlationThreshold).getValue();
    if (checkCorrThreshold) {
      corrThreshold = parameters.getParameter(PostColumnReactionParameters.correlationThreshold)
          .getEmbeddedParameter().getValue();
    } else {
      corrThreshold = 0;
    }

    //Check for formula prediction and set prediction parameters
    this.checkFormulaPred = parameters.getParameter(
        PostColumnReactionParameters.formulaPredictionParameters).getValue();

    if (checkFormulaPred) {
      PostColumnReactionFormulaPredictionParameters predParams = parameters.getParameter(
          PostColumnReactionParameters.formulaPredictionParameters).getEmbeddedParameters();
      ElementalHeuristicParameters heurParams = predParams.getParameter(predElementalRatios)
          .getEmbeddedParameters();
      RDBERestrictionParameters rdbeParams = predParams.getParameter(predRdbeRestrictions)
          .getEmbeddedParameters();
      IsotopePatternScoreParameters isotopeParams = predParams.getParameter(predIsotopeFilter)
          .getEmbeddedParameters();
      MSMSScoreParameters msmsParams = predParams.getParameter(predMsmsFilter)
          .getEmbeddedParameters();

      this.predParamSet = new FormulaPredictionFeatureListParameters();
      this.predParamSet.getParameter(ionization).setValue(predParams.getValue(predIonization));
      this.predParamSet.getParameter(mzTolerance).setValue(predParams.getValue(predMZTolerance));
      this.predParamSet.getParameter(elementalRatios)
          .setValue(predParams.getValue(predElementalRatios));
      this.predParamSet.getParameter(elementalRatios).setEmbeddedParameters(heurParams);
      this.predParamSet.getParameter(rdbeRestrictions)
          .setValue(predParams.getValue(predRdbeRestrictions));
      this.predParamSet.getParameter(rdbeRestrictions).setEmbeddedParameters(rdbeParams);
      this.predParamSet.getParameter(isotopeFilter)
          .setValue(predParams.getValue(predIsotopeFilter));
      this.predParamSet.getParameter(isotopeFilter).setEmbeddedParameters(isotopeParams);
      this.predParamSet.getParameter(msmsFilter).setValue(predParams.getValue(predMsmsFilter));
      this.predParamSet.getParameter(msmsFilter).setEmbeddedParameters(msmsParams);
    }

    setStatus(TaskStatus.WAITING);
    logger.setLevel(Level.FINEST);

    description = "Annotate online reaction products on " + flist.getName();
  }

  @Override
  public String getTaskDescription() {
    return description;
  }

  @Override
  protected @NotNull List<FeatureList> getProcessedFeatureLists() {
    return List.of(flist);
  }

  @Override
  protected void process() {
    setStatus(TaskStatus.PROCESSING);

    //Check if all unreacted reference files are contained in the feature list
    if (!checkUnreactedSelection(flist, unreactedRaws)) {
      setErrorMessage("Feature list " + flist.getName()
          + " does no contain all selected unreacted raw data files.");
      setStatus(TaskStatus.ERROR);
      return;
    }

    //Get the files that are considered as reacted
    final List<RawDataFile> reactedRaws = new ArrayList<>();
    for (RawDataFile file : flist.getRawDataFiles()) {
      if (!unreactedRaws.contains(file)) {
        reactedRaws.add(file);
      }
    }

    logger.finest(() -> flist.getName() + " contains " + reactedRaws.size()
        + " raw data files not classified as reacted.");

    List<FeatureListRow> rows = flist.getRows().stream().sorted(FeatureListRowSorter.MZ_ASCENDING)
        .toList();
    if (rows.isEmpty()) {
      logger.info("Empty feature list " + flist.getName());
      setStatus(TaskStatus.FINISHED);
      return;
    }

    // Filter rows with annotations
    List<FeatureListRow> annotatedRows = rows.stream().filter(FeatureListRow::isIdentified)
        .toList();

    if (annotatedRows.isEmpty()) {
      logger.info("No annotated rows in feature list " + flist.getName());
      setStatus(TaskStatus.FINISHED);
      return;
    }

    //Get correlation map for feature list
    R2RMap<RowsRelationship> correlationMap = flist.getMs1CorrelationMap().orElse(null);
    if (correlationMap == null || correlationMap.isEmpty()) {
      MZmineCore.getDesktop()
          .displayMessage("Run correlation grouping before running this module " + flist.getName());
      setStatus(TaskStatus.FINISHED);
      return;
    }

    // Process correlated rows for each annotated row
    for (FeatureListRow annotatedRow : annotatedRows) {
      correlationMap.streamAllCorrelatedRows(annotatedRow, rows).forEach(rowsRelationship -> {
        if (rowsRelationship.getScore() >= corrThreshold) {
          FeatureListRow correlatedRow = rowsRelationship.getOtherRow(annotatedRow);

          // Check if the feature is present in any unreacted raw files
          boolean isInUnreacted;
          isInUnreacted = unreactedRaws.stream().anyMatch(correlatedRow::hasFeature);

          // Annotate unannotated features
          if (!isInUnreacted) {
            annotateUnannotatedFeature(correlatedRow, annotatedRow);
          }
        }
      });
    }

    setStatus(TaskStatus.FINISHED);
  }

  private void annotateUnannotatedFeature(FeatureListRow correlatedRow, FeatureListRow baseRow) {
    if (correlatedRow.getPreferredAnnotation() == null || correlatedRow.getCompoundAnnotations()
        .isEmpty()) {
      // Alternative way of selecting the base annotation in case multiple annotations are present for the precursor
//      Optional<FeatureAnnotation> annotationWithFormula = CompoundAnnotationUtils.streamFeatureAnnotations(baseRow)
//          .filter(a -> StringUtils.hasValue(a.getFormula())).findFirst();
//
//      if(annotationWithFormula.isPresent()) {
//        FeatureAnnotation annotation = annotationWithFormula.get();
//      }

      // Create name for transformation product based on name of the parent compound, the exact mass and possibly a terminal letter if multiple transformation products with the same parent and nominal m/z exist
      String baseAnnotation = baseRow.getPreferredAnnotationName();
      if (baseAnnotation != null) {
        String roundedMz = String.valueOf(Math.round(correlatedRow.getAverageMZ()));
        String baseTpAnnotation = baseAnnotation + "_ETP_" + roundedMz;

        // Get the current count for this base annotation
        int count = annotationCounts.getOrDefault(baseTpAnnotation, 0);
        String tpAnnotation;

        if (count > 0) {
          char suffix = (char) ('a' + count);
          tpAnnotation = baseTpAnnotation + suffix;
        } else {
          tpAnnotation = baseTpAnnotation;
        }

        // Increment the count for this base annotation
        annotationCounts.put(baseTpAnnotation, count + 1);

        // Annotate the transformation product based on this name
        SimpleCompoundDBAnnotation annotation = new SimpleCompoundDBAnnotation();
        annotation.put(PrecursorMZType.class, correlatedRow.getAverageMZ());
        annotation.put(CompoundNameType.class, tpAnnotation);
        correlatedRow.addCompoundAnnotation(annotation);

        // If automated prediction of molecular formulae for transformation products is selected, the formula is predicted and added to the annotation
        if (this.checkFormulaPred) {
          predictCorrelatedFormula(correlatedRow, baseRow);
          if (correlatedRow.getFormulas() != null && !correlatedRow.getFormulas().isEmpty()) {
            ResultFormula correlatedFormula = correlatedRow.getFormulas().getFirst();
            annotation.setFormula(correlatedFormula.toString());
          }
        }
      }
    }
  }

  public void predictCorrelatedFormula(FeatureListRow correlatedRow, FeatureListRow baseRow) {

    try {
      MolecularFormulaRange molecularFormulaRange = new MolecularFormulaRange();
      List<CompoundDBAnnotation> baseRowCompoundAnnotations = baseRow.getCompoundAnnotations();
      String baseFomrulaString;
      baseFomrulaString = baseRowCompoundAnnotations.getFirst().getFormula();
      assert baseFomrulaString != null;
      IMolecularFormula baseFormula = MolecularFormulaManipulator.getMolecularFormula(
          baseFomrulaString, DefaultChemObjectBuilder.getInstance());

      Iterable<IIsotope> isotopes = baseFormula.isotopes();
      List<FeatureListRow> correlatedRows = new ArrayList<>();  // this is not yet very elegant. The task creates a feature list with one row for each prediction because the prediction task uses a feature list as input. Maybe create one feature list for all correlated rows of one annotation and then run the formula prediction. Alternatively, the prediction task can be adjusted to accept one single feature list row.
      correlatedRows.add(correlatedRow);
      IsotopeFactory iFac = Isotopes.getInstance();
      IIsotope oxygenIsotope = iFac.getMajorIsotope("O");

      for (IIsotope i : isotopes) {
        IIsotope majorIsotope = iFac.getMajorIsotope(i.getSymbol());
        int baseIsotopeCount = baseFormula.getIsotopeCount(i);
        int isotopeCount = baseIsotopeCount;
        if (Objects.equals(i.getSymbol(), "O") || Objects.equals(i.getSymbol(), "H")) {
          isotopeCount = baseIsotopeCount + 8;
        }
        molecularFormulaRange.addIsotope(majorIsotope, 0, isotopeCount);
      }

      if (molecularFormulaRange.contains(oxygenIsotope)) {
        this.predParamSet.getParameter(elements).setValue(molecularFormulaRange);
      } else {
        molecularFormulaRange.addIsotope(oxygenIsotope, 0, 8);
        this.predParamSet.getParameter(elements).setValue(molecularFormulaRange);
      }

      FormulaPredictionFeatureListTask newTask = new FormulaPredictionFeatureListTask(null,
          correlatedRows, this.predParamSet, Instant.now());
      newTask.run();
    } catch (Exception e) {
      logger.severe("Error predicting molecular formula: " + e.getMessage());
    }
  }

  private boolean checkUnreactedSelection(FeatureList aligned, List<RawDataFile> unreactedRaws) {

    List<RawDataFile> flRaws = aligned.getRawDataFiles();

    for (int i = 0; i < unreactedRaws.size(); i++) {
      boolean contained = false;

      for (RawDataFile flRaw : flRaws) {
        if (unreactedRaws.get(i) == flRaw) {
          contained = true;
          break;
        }
      }

      if (!contained) {
        final int i1 = i;
        logger.info(() -> "Feature list " + aligned.getName() + " does not contain raw data files "
            + unreactedRaws.get(i1).getName());
        return false;
      }
    }

    logger.finest(
        () -> "Feature list " + aligned.getName() + " contains all selected blank raw data files.");
    return true;
  }
}
