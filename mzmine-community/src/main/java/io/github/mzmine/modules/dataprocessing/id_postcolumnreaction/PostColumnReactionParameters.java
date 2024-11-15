package io.github.mzmine.modules.dataprocessing.id_postcolumnreaction;

import io.github.mzmine.parameters.impl.SimpleParameterSet;
import io.github.mzmine.parameters.parametertypes.selectors.FeatureListsParameter;
import io.github.mzmine.parameters.parametertypes.selectors.RawDataFilesParameter;

public class PostColumnReactionParameters extends SimpleParameterSet {

  public static final FeatureListsParameter flist = new FeatureListsParameter(
      "Aligned feature list", 1, 1);

  public static final RawDataFilesParameter unreactedRawDataFiles = new RawDataFilesParameter(
      "Unreacted raw data files", 1, Integer.MAX_VALUE  );


  public PostColumnReactionParameters() {
    super(flist, unreactedRawDataFiles);
  }
}
