package io.github.mzmine.modules.dataprocessing.id_postcolumnreaction;

import io.github.mzmine.datamodel.AbundanceMeasure;
import io.github.mzmine.parameters.impl.SimpleParameterSet;
import io.github.mzmine.parameters.parametertypes.ComboParameter;
import io.github.mzmine.parameters.parametertypes.IntegerParameter;
import io.github.mzmine.parameters.parametertypes.OptionalParameter;
import io.github.mzmine.parameters.parametertypes.PercentParameter;
import io.github.mzmine.parameters.parametertypes.selectors.FeatureListsParameter;
import io.github.mzmine.parameters.parametertypes.selectors.RawDataFilesParameter;

public class PostColumnReactionParameters extends SimpleParameterSet {

  public static final FeatureListsParameter flist = new FeatureListsParameter(
      "Aligned feature list", 1, 1);

  public static final RawDataFilesParameter unreactedRawDataFiles = new RawDataFilesParameter(
      "Unreacted raw data files", 1, Integer.MAX_VALUE  );

  public static final IntegerParameter minUnreacted = new IntegerParameter(
      "Minimum # of detection in unreacted references",
      "Specifies in how many of the unreacted files a peak has to be detected.");

  public static final ComboParameter<AbundanceMeasure> quantType = new ComboParameter<AbundanceMeasure>(
      "Quantification", "Use either the features' height or area for the evaluation.",
      AbundanceMeasure.values(), AbundanceMeasure.Height);

  public static final OptionalParameter<PercentParameter> foldChange = new OptionalParameter<>(
      new PercentParameter("Fold change increase",
          "Specifies a percentage of increase of the intensity of a feature. If the intensity in the list to be"
              + " filtered increases less than the given percentage to the unreacted control, it will not be annotated"
              + "as TP.", 3.0, 1.0, 1E5));


  public PostColumnReactionParameters() {
    super(flist, unreactedRawDataFiles);
  }
}
