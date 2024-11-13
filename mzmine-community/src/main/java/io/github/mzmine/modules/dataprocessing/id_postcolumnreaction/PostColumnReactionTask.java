package io.github.mzmine.modules.dataprocessing.id_postcolumnreaction;

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
import io.github.mzmine.taskcontrol.AbstractFeatureListTask;
import io.github.mzmine.taskcontrol.TaskStatus;
import io.github.mzmine.util.FeatureListRowSorter;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

public class PostColumnReactionTask extends AbstractFeatureListTask {

  private static final Logger logger = Logger.getLogger(OnlineLcReactivityTask.class.getName());

  private final FeatureList flist;
  private final String description;

  public PostColumnReactionTask(@NotNull ParameterSet parameters, FeatureList flist,
      @NotNull Instant moduleCallDate) {
    super(null, moduleCallDate, parameters, OnlineLcReactivityModule.class);
    this.flist = flist;

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
        // Annotate unannotated features
        annotateUnannotatedFeature(correlatedRow, annotatedRow);
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
}
