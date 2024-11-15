package io.github.mzmine.modules.dataprocessing.id_postcolumnreaction;

import io.github.mzmine.datamodel.MZmineProject;
import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.datamodel.features.FeatureList;
import io.github.mzmine.datamodel.features.FeatureListRow;
import io.github.mzmine.datamodel.features.compoundannotations.SimpleCompoundDBAnnotation;
import io.github.mzmine.datamodel.features.correlation.R2RMap;
import io.github.mzmine.datamodel.features.correlation.RowsRelationship;
import io.github.mzmine.datamodel.features.types.annotations.CompoundNameType;
import io.github.mzmine.datamodel.features.types.numbers.PrecursorMZType;
import io.github.mzmine.main.MZmineCore;
import io.github.mzmine.modules.dataprocessing.id_online_reactivity.OnlineLcReactivityModule;
import io.github.mzmine.modules.dataprocessing.id_online_reactivity.OnlineLcReactivityTask;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.parameters.parametertypes.selectors.RawDataFilesSelection;
import io.github.mzmine.taskcontrol.AbstractFeatureListTask;
import io.github.mzmine.taskcontrol.TaskStatus;
import io.github.mzmine.util.FeatureListRowSorter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

public class PostColumnReactionTask extends AbstractFeatureListTask {

  private static final Logger logger = Logger.getLogger(OnlineLcReactivityTask.class.getName());

  private final FeatureList flist;
  private final String description;

  private final int totalRows;

  private MZmineProject project;
  private ParameterSet parameters;
  private RawDataFilesSelection unreactedSelection;
  private List<RawDataFile> unreactedRaws;

  public PostColumnReactionTask(@NotNull ParameterSet parameters,
      @NotNull Instant moduleCallDate) {
    super(null, moduleCallDate, parameters, OnlineLcReactivityModule.class);

    this.parameters = parameters;
    this.unreactedSelection = parameters.getParameter(
        PostColumnReactionParameters.unreactedRawDataFiles).getValue();
    this.unreactedRaws = List.of(unreactedSelection.getMatchingRawDataFiles().clone());
    this.flist = parameters.getParameter(
            PostColumnReactionParameters.flist).getValue()
        .getMatchingFeatureLists()[0];
    totalRows = flist.getNumberOfRows();

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

    if (!checkUnreactedSelection(flist, unreactedRaws)) {
      setErrorMessage("Feature list " + flist.getName()
          + " does no contain all selected unreacted raw data files.");
      setStatus(TaskStatus.ERROR);
      return;
    }

    // get the files that are considered as reacted
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
    List<FeatureListRow> annotatedRows = rows.stream().filter(FeatureListRow::isIdentified).toList();

    if (annotatedRows.isEmpty()) {
      logger.info("No annotated rows in feature list " + flist.getName());
      setStatus(TaskStatus.FINISHED);
      return;
    }

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
        FeatureListRow correlatedRow = rowsRelationship.getOtherRow(annotatedRow);

        // Check if the feature is present in any unreacted raw files
        boolean isInUnreacted = unreactedRaws.stream()
            .anyMatch(unreactedRaw -> correlatedRow.hasFeature(unreactedRaw));

        if (!isInUnreacted) {
          // Annotate unannotated features
          annotateUnannotatedFeature(correlatedRow, annotatedRow);
        }
      });
    }

    setStatus(TaskStatus.FINISHED);
  }

  private void annotateUnannotatedFeature(FeatureListRow correlatedRow, FeatureListRow baseRow) {
    if (correlatedRow.getPreferredAnnotation() == null || correlatedRow.getCompoundAnnotations().isEmpty()) {
      String baseAnnotation = baseRow.getPreferredAnnotationName();
      if (baseAnnotation != null) {
        String tpAnnotation = baseAnnotation + "_ETP_" + correlatedRow.getID(); // Using ID for uniqueness
        SimpleCompoundDBAnnotation annotation = new SimpleCompoundDBAnnotation();

        annotation.put(PrecursorMZType.class, correlatedRow.getAverageMZ());
        annotation.put(CompoundNameType.class, tpAnnotation);
        correlatedRow.addCompoundAnnotation(annotation);
      }
    }
  }

  private boolean checkUnreactedSelection(FeatureList aligned, List<RawDataFile> unreactedRaws) {

    List<RawDataFile> flRaws = aligned.getRawDataFiles();

    for (int i = 0; i < unreactedRaws.size(); i++) {
      boolean contained = false;

      for (RawDataFile flRaw : flRaws) {
        if (unreactedRaws.get(i) == flRaw) {
          contained = true;
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
