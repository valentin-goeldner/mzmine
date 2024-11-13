package io.github.mzmine.modules.dataprocessing.id_postcolumnreaction;

import io.github.mzmine.parameters.impl.SimpleParameterSet;
import io.github.mzmine.parameters.parametertypes.selectors.FeatureListsParameter;

public class PostColumnReactionParameters extends SimpleParameterSet {

  public static final FeatureListsParameter flists = new FeatureListsParameter();

  //remove when adding the unreacted control
  public PostColumnReactionParameters() {
    super(flists);

  }
}
