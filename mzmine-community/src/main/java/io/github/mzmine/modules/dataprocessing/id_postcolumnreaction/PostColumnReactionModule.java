package io.github.mzmine.modules.dataprocessing.id_postcolumnreaction;

import io.github.mzmine.datamodel.MZmineProject;
import io.github.mzmine.datamodel.features.ModularFeatureList;
import io.github.mzmine.modules.MZmineModuleCategory;
import io.github.mzmine.modules.MZmineProcessingModule;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.taskcontrol.Task;
import io.github.mzmine.util.ExitCode;
import java.time.Instant;
import java.util.Collection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PostColumnReactionModule implements MZmineProcessingModule {

  @Override
  public @NotNull String getName() {
    return "Post Column Reaction";
  }

  @Override
  public @Nullable Class<? extends ParameterSet> getParameterSetClass() {
    return PostColumnReactionParameters.class;
  }

  @Override
  public @NotNull String getDescription() {
    return """
        Annotates correlated features within one group as transformation products (ETPs) of a
        feature with compound database annotation within this group.
        """;
  }

  @Override
  public @NotNull ExitCode runModule(@NotNull MZmineProject project,
      @NotNull ParameterSet parameters, @NotNull Collection<Task> tasks,
      @NotNull Instant moduleCallDate) {
    for (ModularFeatureList flist : parameters.getValue(PostColumnReactionParameters.flist)
        .getMatchingFeatureLists()) {
      tasks.add(new PostColumnReactionTask(parameters, moduleCallDate));
    }
    return ExitCode.OK;
  }

  @Override
  public @NotNull MZmineModuleCategory getModuleCategory() {
    return MZmineModuleCategory.ANNOTATION;
  }
}