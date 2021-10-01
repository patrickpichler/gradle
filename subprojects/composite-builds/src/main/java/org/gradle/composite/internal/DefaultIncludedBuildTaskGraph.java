/*
 * Copyright 2017 the original author or authors.
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
package org.gradle.composite.internal;

import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.internal.build.BuildLifecycleController;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.ExecutionResult;
import org.gradle.internal.build.ExportedTaskNode;
import org.gradle.internal.buildtree.BuildTreeWorkGraph;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.ManagedExecutor;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.taskgraph.CalculateTreeTaskGraphBuildOperationType;
import org.gradle.internal.work.WorkerLeaseService;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


public class DefaultIncludedBuildTaskGraph implements IncludedBuildTaskGraph, Closeable {
    private enum State {
        NotCreated, NotPrepared, QueuingTasks, ReadyToRun, Running, Finished
    }

    private final BuildOperationExecutor buildOperationExecutor;
    private final BuildStateRegistry buildRegistry;
    private final WorkerLeaseService workerLeaseService;
    private final ProjectStateRegistry projectStateRegistry;
    private final ManagedExecutor executorService;
    private Thread owner;
    private State state = State.NotCreated;
    private BuildControllers controllers;

    public DefaultIncludedBuildTaskGraph(
        ExecutorFactory executorFactory,
        BuildOperationExecutor buildOperationExecutor,
        BuildStateRegistry buildRegistry,
        ProjectStateRegistry projectStateRegistry,
        WorkerLeaseService workerLeaseService
    ) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.buildRegistry = buildRegistry;
        this.projectStateRegistry = projectStateRegistry;
        this.executorService = executorFactory.create("included builds");
        this.workerLeaseService = workerLeaseService;
        this.controllers = createControllers();
    }

    private DefaultBuildControllers createControllers() {
        return new DefaultBuildControllers(executorService, buildRegistry, projectStateRegistry, workerLeaseService);
    }

    @Override
    public <T> T withNewTaskGraph(Function<? super BuildTreeWorkGraph, T> action) {
        Thread currentOwner;
        State currentState;
        BuildControllers currentControllers;
        synchronized (this) {
            if (state != State.Running) {
                if (owner != null && owner != Thread.currentThread()) {
                    throw new IllegalStateException("This task graph is already in use.");
                }
            }
            // Else, some other thread is currently running tasks.
            // The later can happen when a task performs dependency resolution without declaring it and the resolution
            // includes a dependency substitution on an included build or a source dependency build
            // Allow this to proceed, but this should become an error at some point
            currentOwner = owner;
            currentState = state;
            currentControllers = controllers;
            owner = Thread.currentThread();
            state = State.NotPrepared;
            controllers = createControllers();
        }

        try {
            return action.apply(new DefaultBuildTreeWorkGraph());
        } finally {
            controllers.close();
            synchronized (this) {
                owner = currentOwner;
                state = currentState;
                controllers = currentControllers;
            }
        }
    }

    private void doPrepareTaskGraph(Consumer<? super BuildTreeWorkGraph.Builder> action) {
        withState(() -> {
            expectInState(State.NotPrepared);
            state = State.QueuingTasks;
            buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) {
                    action.accept(new DefaultBuildTreeWorkGraphBuilder());
                    controllers.populateTaskGraphs();
                    context.setResult(new CalculateTreeTaskGraphBuildOperationType.Result() {
                    });
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor.displayName("Calculate build tree task graph")
                        .details(new CalculateTreeTaskGraphBuildOperationType.Details() {
                        });
                }
            });
            state = State.ReadyToRun;
            return null;
        });
    }

    @Override
    public IncludedBuildTaskResource locateTask(BuildIdentifier targetBuild, TaskInternal task) {
        return withState(() -> {
            assertCanLocateTask();
            BuildController buildController = controllers.getBuildController(targetBuild);
            return new TaskBackedResource(buildController, buildController.locateTask(task));
        });
    }

    @Override
    public IncludedBuildTaskResource locateTask(BuildIdentifier targetBuild, String taskPath) {
        return withState(() -> {
            assertCanLocateTask();
            BuildController buildController = controllers.getBuildController(targetBuild);
            return new TaskBackedResource(buildController, buildController.locateTask(taskPath));
        });
    }

    private void queueForExecution(BuildController buildController, ExportedTaskNode taskNode) {
        withState(() -> {
            assertCanQueueTask();
            buildController.queueForExecution(taskNode);
            return null;
        });
    }

    private ExecutionResult<Void> doRunWork() {
        return withState(() -> {
            expectInState(State.ReadyToRun);
            state = State.Running;
            try {
                controllers.startTaskExecution();
                return controllers.awaitTaskCompletion();
            } finally {
                state = State.Finished;
            }
        });
    }

    @Override
    public void close() throws IOException {
        CompositeStoppable.stoppable(controllers, executorService);
    }

    private void assertCanLocateTask() {
        if (state == State.NotPrepared) {
            return;
        }
        assertCanQueueTask();
    }

    private void assertCanQueueTask() {
        expectInState(State.QueuingTasks);
    }

    private void expectInState(State expectedState) {
        if (state != expectedState) {
            throw unexpectedState();
        }
    }

    private IllegalStateException unexpectedState() {
        return new IllegalStateException("Work graph is in an unexpected state: " + state);
    }

    private <T> T withState(Supplier<T> action) {
        Thread currentOwner;
        synchronized (this) {
            currentOwner = owner;
            if (owner == null) {
                owner = Thread.currentThread();
            } else if (owner != Thread.currentThread()) {
                // Currently, only a single thread should work with the task graph at a time
                throw new IllegalStateException("This task graph is already in use.");
            }
        }
        try {
            return action.get();
        } finally {
            synchronized (this) {
                if (owner != Thread.currentThread()) {
                    throw new IllegalStateException("This task graph is in use by another thread.");
                }
                owner = currentOwner;
            }
        }
    }

    private class DefaultBuildTreeWorkGraphBuilder implements BuildTreeWorkGraph.Builder {
        @Override
        public void withWorkGraph(BuildState target, Consumer<? super BuildLifecycleController.WorkGraphBuilder> action) {
            controllers.getBuildController(target.getBuildIdentifier()).populateWorkGraph(action);
        }
    }

    private class DefaultBuildTreeWorkGraph implements BuildTreeWorkGraph {
        @Override
        public void prepareTaskGraph(Consumer<? super Builder> action) {
            doPrepareTaskGraph(action);
        }

        @Override
        public ExecutionResult<Void> runWork() {
            return doRunWork();
        }
    }

    private class TaskBackedResource implements IncludedBuildTaskResource {
        private final BuildController buildController;
        private final ExportedTaskNode taskNode;

        public TaskBackedResource(BuildController buildController, ExportedTaskNode taskNode) {
            this.buildController = buildController;
            this.taskNode = taskNode;
        }

        @Override
        public void queueForExecution() {
            DefaultIncludedBuildTaskGraph.this.queueForExecution(buildController, taskNode);
        }

        @Override
        public TaskInternal getTask() {
            return taskNode.getTask();
        }

        @Override
        public State getTaskState() {
            return taskNode.getTaskState();
        }
    }

}
