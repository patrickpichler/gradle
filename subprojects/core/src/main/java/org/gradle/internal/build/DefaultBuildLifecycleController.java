/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.internal.build;

import org.gradle.BuildListener;
import org.gradle.BuildResult;
import org.gradle.api.Task;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.execution.BuildWorkExecutor;
import org.gradle.execution.plan.Node;
import org.gradle.execution.taskgraph.TaskExecutionGraphInternal;
import org.gradle.initialization.BuildCompletionListener;
import org.gradle.initialization.exception.ExceptionAnalyser;
import org.gradle.initialization.internal.InternalBuildFinishedListener;
import org.gradle.internal.buildtree.BuildTreeWorkGraph;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.execution.BuildOutputCleanupRegistry;
import org.gradle.internal.model.StateTransitionController;
import org.gradle.internal.model.StateTransitionControllerFactory;
import org.gradle.internal.service.scopes.BuildScopeServices;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class DefaultBuildLifecycleController implements BuildLifecycleController {
    private enum State implements StateTransitionController.State {
        // Configuring the build, can access build model
        Configure,
        // Scheduling tasks for execution
        TaskSchedule,
        ReadyToRun,
        // build has finished and should do no further work
        Finished
    }

    private final ExceptionAnalyser exceptionAnalyser;
    private final BuildListener buildListener;
    private final BuildCompletionListener buildCompletionListener;
    private final InternalBuildFinishedListener buildFinishedListener;
    private final BuildWorkPreparer workPreparer;
    private final BuildWorkExecutor workExecutor;
    private final BuildScopeServices buildServices;
    private final BuildModelController modelController;
    private final StateTransitionController<State> state;
    private final GradleInternal gradle;
    private boolean hasTasks;

    public DefaultBuildLifecycleController(
        GradleInternal gradle,
        BuildModelController buildModelController,
        ExceptionAnalyser exceptionAnalyser,
        BuildListener buildListener,
        BuildCompletionListener buildCompletionListener,
        InternalBuildFinishedListener buildFinishedListener,
        BuildWorkPreparer workPreparer,
        BuildWorkExecutor workExecutor,
        BuildScopeServices buildServices,
        StateTransitionControllerFactory controllerFactory
    ) {
        this.gradle = gradle;
        this.modelController = buildModelController;
        this.exceptionAnalyser = exceptionAnalyser;
        this.buildListener = buildListener;
        this.workPreparer = workPreparer;
        this.workExecutor = workExecutor;
        this.buildCompletionListener = buildCompletionListener;
        this.buildFinishedListener = buildFinishedListener;
        this.buildServices = buildServices;
        this.state = controllerFactory.newController(State.Configure);
    }

    @Override
    public GradleInternal getGradle() {
        // Should not ignore other threads, however it is currently possible for this to be queried by tasks at execution time (that is, when another thread is
        // transitioning the task graph state). Instead, it may be better to:
        // - have the threads use some specific immutable view of the build model state instead of requiring direct access to the build model.
        // - not have a thread blocked around task execution, so that other threads can use the build model.
        // - maybe split the states into one for the build model and one for the task graph.
        state.assertNotInState(State.Finished);
        return gradle;
    }

    @Override
    public SettingsInternal getLoadedSettings() {
        // Should not ignore other threads. See above.
        return state.notInStateIgnoreOtherThreads(State.Finished, modelController::getLoadedSettings);
    }

    @Override
    public GradleInternal getConfiguredBuild() {
        // Should not ignore other threads. See above.
        return state.notInStateIgnoreOtherThreads(State.Finished, modelController::getConfiguredModel);
    }

    @Override
    public void prepareToScheduleTasks() {
        state.maybeTransition(State.Configure, State.TaskSchedule, () -> {
            hasTasks = true;
            modelController.prepareToScheduleTasks();
        });
    }

    @Override
    public void addRequestedTasks(BuildTreeWorkGraph.Builder builder) {
        builder.withWorkGraph(gradle.getOwner(), graph -> graph.addRequestedTasks());
    }

    @Override
    public void populateWorkGraph(Consumer<? super WorkGraphBuilder> action) {
        state.inState(State.TaskSchedule, () -> workPreparer.populateWorkGraph(gradle, tasks -> action.accept(new DefaultWorkGraphBuilder(tasks))));
    }

    @Override
    public void finalizeWorkGraph() {
        state.transition(State.TaskSchedule, State.ReadyToRun, () -> {
            TaskExecutionGraphInternal taskGraph = gradle.getTaskGraph();
            taskGraph.populate();
            BuildOutputCleanupRegistry buildOutputCleanupRegistry = gradle.getServices().get(BuildOutputCleanupRegistry.class);
            buildOutputCleanupRegistry.resolveOutputs();
        });
    }

    @Override
    public ExecutionResult<Void> executeTasks() {
        // Execute tasks and transition back to "configure", as this build may run more tasks;
        return state.tryTransition(State.ReadyToRun, State.Configure, () -> workExecutor.execute(gradle));
    }

    @Override
    public ExecutionResult<Void> finishBuild(@Nullable Throwable failure) {
        return state.finish(State.Finished, stageFailures -> {
            // Fire the build finished events even if nothing has happened to this build, because quite a lot of internal infrastructure
            // adds listeners and expects to see a build finished event. Infrastructure should not be using the public listener types
            // In addition, they almost all should be using a build tree scoped event instead of a build scoped event

            Throwable reportableFailure = failure;
            if (reportableFailure == null && !stageFailures.getFailures().isEmpty()) {
                reportableFailure = exceptionAnalyser.transform(stageFailures.getFailures());
            }
            BuildResult buildResult = new BuildResult(hasTasks ? "Build" : "Configure", gradle, reportableFailure);
            ExecutionResult<Void> finishResult;
            try {
                buildListener.buildFinished(buildResult);
                buildFinishedListener.buildFinished((GradleInternal) buildResult.getGradle(), buildResult.getFailure() != null);
                finishResult = ExecutionResult.succeeded();
            } catch (Throwable t) {
                finishResult = ExecutionResult.failed(t);
            }
            return finishResult;
        });
    }

    /**
     * <p>Adds a listener to this build instance. The listener is notified of events which occur during the execution of the build.
     * See {@link org.gradle.api.invocation.Gradle#addListener(Object)} for supported listener types.</p>
     *
     * @param listener The listener to add. Has no effect if the listener has already been added.
     */
    @Override
    public void addListener(Object listener) {
        getGradle().addListener(listener);
    }

    @Override
    public void stop() {
        state.assertInState(State.Finished);
        try {
            CompositeStoppable.stoppable(buildServices).stop();
        } finally {
            buildCompletionListener.completed();
        }
    }

    private class DefaultWorkGraphBuilder implements WorkGraphBuilder {
        private final TaskExecutionGraphInternal taskGraph;

        public DefaultWorkGraphBuilder(TaskExecutionGraphInternal taskGraph) {
            this.taskGraph = taskGraph;
        }

        @Override
        public void addRequestedTasks() {
            modelController.scheduleRequestedTasks();
        }

        @Override
        public void addEntryTasks(List<? extends Task> tasks) {
            for (Task task : tasks) {
                taskGraph.addEntryTasks(Collections.singletonList(task));
            }
        }

        @Override
        public void addNodes(List<? extends Node> nodes) {
            taskGraph.addNodes(nodes);
        }
    }
}
