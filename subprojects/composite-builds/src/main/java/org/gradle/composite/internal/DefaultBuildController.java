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

package org.gradle.composite.internal;

import org.gradle.api.CircularReferenceException;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.execution.plan.Node;
import org.gradle.execution.plan.TaskNode;
import org.gradle.execution.plan.TaskNodeFactory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.build.BuildLifecycleController;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.ExecutionResult;
import org.gradle.internal.build.ExportedTaskNode;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.graph.CachingDirectedGraphWalker;
import org.gradle.internal.graph.DirectedGraphRenderer;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.operations.BuildOperationRef;
import org.gradle.internal.operations.CurrentBuildOperationRef;
import org.gradle.internal.work.WorkerLeaseRegistry;
import org.gradle.internal.work.WorkerLeaseService;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

class DefaultBuildController implements BuildController, Stoppable {
    private enum State {
        DiscoveringTasks, ReadyToRun, RunningTasks, Finished
    }

    private final BuildState build;
    private final Set<ExportedTaskNode> scheduled = new LinkedHashSet<>();
    private final Set<ExportedTaskNode> queuedForExecution = new LinkedHashSet<>();
    private final WorkerLeaseRegistry.WorkerLease parentLease;
    private final WorkerLeaseService workerLeaseService;
    private final ProjectStateRegistry projectStateRegistry;

    private boolean somethingScheduled = false;
    private State state = State.DiscoveringTasks;
    // Fields guarded by lock
    private final Lock lock = new ReentrantLock();
    private final Condition stateChange = lock.newCondition();
    private boolean finished;
    private final List<Throwable> executionFailures = new ArrayList<>();

    public DefaultBuildController(BuildState build, ProjectStateRegistry projectStateRegistry, WorkerLeaseService workerLeaseService) {
        this.build = build;
        this.projectStateRegistry = projectStateRegistry;
        this.parentLease = workerLeaseService.getCurrentWorkerLease();
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public ExportedTaskNode locateTask(TaskInternal task) {
        assertInState(State.DiscoveringTasks);
        return build.getWorkGraph().locateTask(task);
    }

    @Override
    public ExportedTaskNode locateTask(String taskPath) {
        assertInState(State.DiscoveringTasks);
        return build.getWorkGraph().locateTask(taskPath);
    }

    @Override
    public void queueForExecution(ExportedTaskNode taskNode) {
        assertInState(State.DiscoveringTasks);
        somethingScheduled = true;
        queuedForExecution.add(taskNode);
    }

    @Override
    public void populateWorkGraph(Consumer<? super BuildLifecycleController.WorkGraphBuilder> action) {
        assertInState(State.DiscoveringTasks);
        somethingScheduled = true;
        build.getWorkGraph().populateWorkGraph(action);
    }

    @Override
    public boolean populateTaskGraph() {
        // Can be called after validating task graph
        if (state == State.ReadyToRun) {
            return false;
        }
        assertInState(State.DiscoveringTasks);

        queuedForExecution.removeAll(scheduled);
        if (queuedForExecution.isEmpty()) {
            return false;
        }

        boolean added = build.getWorkGraph().schedule(queuedForExecution);
        scheduled.addAll(queuedForExecution);
        queuedForExecution.clear();
        return added;
    }

    @Override
    public void prepareForExecution() {
        if (state == State.ReadyToRun) {
            return;
        }
        assertInState(State.DiscoveringTasks);
        if (!queuedForExecution.isEmpty()) {
            throw new IllegalStateException("Queued tasks have not been scheduled.");
        }

        // TODO - This check should live in the task execution plan, so that it can reuse checks that have already been performed and
        //   also check for cycles across all nodes
        Set<TaskInternal> visited = new HashSet<>();
        Set<TaskInternal> visiting = new HashSet<>();
        for (ExportedTaskNode node : scheduled) {
            checkForCyclesFor(node.getTask(), visited, visiting);
        }
        build.getWorkGraph().prepareForExecution();

        state = State.ReadyToRun;
    }

    @Override
    public void startTaskExecution(ExecutorService executorService) {
        assertInState(State.ReadyToRun);
        state = State.RunningTasks;
        if (somethingScheduled) {
            doStartTaskExecution(executorService);
        }
    }

    @Override
    public ExecutionResult<Void> awaitTaskCompletion() {
        assertInState(State.RunningTasks);
        ExecutionResult<Void> result;
        if (somethingScheduled) {
            List<Throwable> failures = new ArrayList<>();
            doAwaitTaskCompletion(failures::add);
            result = ExecutionResult.maybeFailed(failures);
        } else {
            result = ExecutionResult.succeeded();
        }
        scheduled.clear();
        state = State.Finished;
        return result;
    }

    @Override
    public void stop() {
        if (state == State.RunningTasks) {
            throw new IllegalStateException("Build is currently running tasks.");
        }
    }

    protected void doStartTaskExecution(ExecutorService executorService) {
        executorService.submit(new BuildOpRunnable(CurrentBuildOperationRef.instance().get()));
    }

    protected void doAwaitTaskCompletion(Consumer<? super Throwable> executionFailures) {
        // Ensure that this thread does not hold locks while waiting and so prevent this work from completing
        projectStateRegistry.blocking(() -> {
            lock.lock();
            try {
                while (!finished) {
                    awaitStateChange();
                }
                for (Throwable taskFailure : this.executionFailures) {
                    executionFailures.accept(taskFailure);
                }
                this.executionFailures.clear();
            } finally {
                lock.unlock();
            }
        });
    }

    private void assertInState(State expectedState) {
        if (state != expectedState) {
            throw new IllegalStateException("Build is in unexpected state: " + state);
        }
    }

    private void checkForCyclesFor(TaskInternal task, Set<TaskInternal> visited, Set<TaskInternal> visiting) {
        if (visited.contains(task)) {
            // Already checked
            return;
        }
        if (!visiting.add(task)) {
            // Visiting dependencies -> have found a cycle
            CachingDirectedGraphWalker<TaskInternal, Void> graphWalker = new CachingDirectedGraphWalker<>((node, values, connectedNodes) -> visitDependenciesOf(node, connectedNodes::add));
            graphWalker.add(task);
            List<Set<TaskInternal>> cycles = graphWalker.findCycles();
            Set<TaskInternal> cycle = cycles.get(0);

            DirectedGraphRenderer<TaskInternal> graphRenderer = new DirectedGraphRenderer<>(
                (node, output) -> output.withStyle(StyledTextOutput.Style.Identifier).text(node.getIdentityPath()),
                (node, values, connectedNodes) -> visitDependenciesOf(node, dep -> {
                    if (cycle.contains(dep)) {
                        connectedNodes.add(dep);
                    }
                })
            );
            StringWriter writer = new StringWriter();
            graphRenderer.renderTo(task, writer);
            throw new CircularReferenceException(String.format("Circular dependency between the following tasks:%n%s", writer.toString()));
        }
        visitDependenciesOf(task, dep -> checkForCyclesFor(dep, visited, visiting));
        visiting.remove(task);
        visited.add(task);
    }

    private void visitDependenciesOf(TaskInternal task, Consumer<TaskInternal> consumer) {
        TaskNodeFactory taskNodeFactory = ((GradleInternal) task.getProject().getGradle()).getServices().get(TaskNodeFactory.class);
        TaskNode node = taskNodeFactory.getOrCreateNode(task);
        for (Node dependency : node.getAllSuccessors()) {
            if (dependency instanceof TaskNode) {
                consumer.accept(((TaskNode) dependency).getTask());
            }
        }
    }

    private void run() {
        try {
            workerLeaseService.withSharedLease(parentLease, this::doBuild);
        } catch (Throwable t) {
            executionFailed(t);
        } finally {
            markFinished();
        }
    }

    private void markFinished() {
        lock.lock();
        try {
            finished = true;
            stateChange.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void awaitStateChange() {
        try {
            stateChange.await();
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    private void doBuild() {
        ExecutionResult<Void> result = build.getWorkGraph().execute();
        executionFinished(result);
    }

    private void executionFinished(ExecutionResult<Void> result) {
        lock.lock();
        try {
            executionFailures.addAll(result.getFailures());
        } finally {
            lock.unlock();
        }
    }

    private void executionFailed(Throwable failure) {
        lock.lock();
        try {
            executionFailures.add(failure);
        } finally {
            lock.unlock();
        }
    }

    private class BuildOpRunnable implements Runnable {
        private final BuildOperationRef parentBuildOperation;

        BuildOpRunnable(BuildOperationRef parentBuildOperation) {
            this.parentBuildOperation = parentBuildOperation;
        }

        @Override
        public void run() {
            CurrentBuildOperationRef.instance().set(parentBuildOperation);
            try {
                DefaultBuildController.this.run();
            } finally {
                CurrentBuildOperationRef.instance().set(null);
            }
        }
    }
}
