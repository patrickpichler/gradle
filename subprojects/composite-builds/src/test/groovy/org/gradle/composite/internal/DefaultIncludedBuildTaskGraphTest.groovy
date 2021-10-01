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

package org.gradle.composite.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.component.BuildIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.internal.artifacts.DefaultBuildIdentifier
import org.gradle.api.internal.project.ProjectRegistry
import org.gradle.api.internal.project.ProjectState
import org.gradle.api.internal.project.ProjectStateRegistry
import org.gradle.initialization.DefaultProjectDescriptor
import org.gradle.internal.Factory
import org.gradle.internal.build.BuildProjectRegistry
import org.gradle.internal.build.BuildState
import org.gradle.internal.build.BuildStateRegistry
import org.gradle.internal.build.BuildWorkGraph
import org.gradle.internal.build.ExecutionResult
import org.gradle.internal.build.IncludedBuildState
import org.gradle.internal.operations.TestBuildOperationExecutor
import org.gradle.test.fixtures.concurrent.ConcurrentSpec
import org.gradle.test.fixtures.work.TestWorkerLeaseService

class DefaultIncludedBuildTaskGraphTest extends ConcurrentSpec {
    def buildStateRegistry = Mock(BuildStateRegistry)
    def projectStateRegistry = new TestProjectStateRegistry()
    def workerLeaseService = new TestWorkerLeaseService()
    def graph = new DefaultIncludedBuildTaskGraph(executorFactory, new TestBuildOperationExecutor(), buildStateRegistry, projectStateRegistry, workerLeaseService)

    def "does nothing when nothing scheduled"() {
        when:
        graph.withNewTaskGraph { g ->
            g.prepareTaskGraph { b ->
            }
            g.runWork().rethrow()
        }

        then:
        0 * _
    }

    def "finalizes graph for a build when something scheduled"() {
        given:
        def id = Stub(BuildIdentifier)
        def workGraph = Mock(BuildWorkGraph)
        def build = build(id, workGraph)

        when:
        graph.withNewTaskGraph { g ->
            g.prepareTaskGraph { b ->
                b.withWorkGraph(build) {}
            }
            g.runWork().rethrow()
        }

        then:
        1 * workGraph.populateWorkGraph(_)
        1 * workGraph.prepareForExecution()
        1 * workGraph.execute() >> ExecutionResult.succeeded()
    }

    def "cannot schedule tasks when graph has not been created"() {
        when:
        graph.locateTask(DefaultBuildIdentifier.ROOT, ":task").queueForExecution()

        then:
        def e = thrown(IllegalStateException)
        e.message == "Work graph is in an unexpected state: NotCreated"
    }

    def "cannot schedule tasks when after graph has finished execution"() {
        when:
        graph.withNewTaskGraph { 12 }
        graph.locateTask(DefaultBuildIdentifier.ROOT, ":task").queueForExecution()

        then:
        def e = thrown(IllegalStateException)
        e.message == "Work graph is in an unexpected state: NotCreated"
    }

    def "cannot schedule tasks when graph is not yet being prepared for execution"() {
        given:
        def id = Stub(BuildIdentifier)
        def build = build(id)

        when:
        graph.withNewTaskGraph { g ->
            graph.locateTask(id, ":task").queueForExecution()
        }

        then:
        def e = thrown(IllegalStateException)
        e.message == "Work graph is in an unexpected state: NotPrepared"
    }

    def "cannot schedule tasks when graph has been prepared for execution"() {
        when:
        graph.withNewTaskGraph { g ->
            g.prepareTaskGraph {
            }
            graph.locateTask(DefaultBuildIdentifier.ROOT, ":task").queueForExecution()
        }

        then:
        def e = thrown(IllegalStateException)
        e.message == "Work graph is in an unexpected state: ReadyToRun"
    }

    def "cannot schedule tasks when graph has started task execution"() {
        given:
        def id = Stub(BuildIdentifier)
        def workGraph = Mock(BuildWorkGraph)
        def build = build(id, workGraph)

        workGraph.execute() >> {
            graph.locateTask(DefaultBuildIdentifier.ROOT, ":task").queueForExecution()
        }

        when:
        graph.withNewTaskGraph { g ->
            g.prepareTaskGraph { b ->
                b.withWorkGraph(build) {}
            }
            g.runWork().rethrow()
        }

        then:
        def e = thrown(IllegalStateException)
        e.message == "This task graph is already in use."
    }

    def "cannot schedule tasks when graph has completed task execution"() {
        when:
        graph.withNewTaskGraph { g ->
            g.prepareTaskGraph {
            }
            g.runWork()
            graph.locateTask(DefaultBuildIdentifier.ROOT, ":task").queueForExecution()
        }

        then:
        def e = thrown(IllegalStateException)
        e.message == "Work graph is in an unexpected state: Finished"
    }

    BuildState build(BuildIdentifier id, BuildWorkGraph workGraph = null) {
        def build = Mock(IncludedBuildState)
        _ * build.buildIdentifier >> id
        _ * build.workGraph >> (workGraph ?: Stub(BuildWorkGraph))
        _ * buildStateRegistry.getBuild(id) >> build
        return build
    }

    class TestProjectStateRegistry implements ProjectStateRegistry {
        @Override
        Collection<? extends ProjectState> getAllProjects() {
            throw new UnsupportedOperationException()
        }

        @Override
        ProjectState stateFor(Project project) throws IllegalArgumentException {
            throw new UnsupportedOperationException()
        }

        @Override
        ProjectState stateFor(ProjectComponentIdentifier identifier) throws IllegalArgumentException {
            throw new UnsupportedOperationException()
        }

        @Override
        BuildProjectRegistry projectsFor(BuildIdentifier buildIdentifier) throws IllegalArgumentException {
            throw new UnsupportedOperationException()
        }

        @Override
        void registerProjects(BuildState build, ProjectRegistry<DefaultProjectDescriptor> projectRegistry) {
            throw new UnsupportedOperationException()
        }

        @Override
        ProjectState registerProject(BuildState owner, DefaultProjectDescriptor projectDescriptor) {
            throw new UnsupportedOperationException()
        }

        @Override
        void withMutableStateOfAllProjects(Runnable runnable) {
            throw new UnsupportedOperationException()
        }

        @Override
        def <T> T withMutableStateOfAllProjects(Factory<T> factory) {
            throw new UnsupportedOperationException()
        }

        @Override
        def <T> T allowUncontrolledAccessToAnyProject(Factory<T> factory) {
            throw new UnsupportedOperationException()
        }

        @Override
        void blocking(Runnable runnable) {
            runnable.run()
        }
    }
}
