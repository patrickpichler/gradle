The Gradle team is excited to announce Gradle @version@.

This release [introduces a declarative test suite API](#test-suites) for JVM projects, adds [support for building projects with Java 17](#java17),  and updates the Scala plugin to support [Scala 3](#scala).

There are also changes to make builds [more reliable](#reliability), provide [additional details to IDEs when downloading dependencies](#tooling-api), improve [untracked files in custom plugins](#untracked), several [bug fixes](#fixed-issues) and more.

We would like to thank the following community members for their contributions to this release of Gradle:
<<<<<<< HEAD
<!-- 
Include only their name, impactful features should be called out separately below.
 [Some person](https://github.com/some-person)
-->
=======

[Attix Zhang](https://github.com/attix-zhang),
[anatawa12](https://github.com/anatawa12),
[Anil Kumar Myla](https://github.com/anilkumarmyla),
[Marcono1234](https://github.com/Marcono1234),
[Nicola Corti](https://github.com/cortinico),
[Scott Palmer](https://github.com/swpalmer),
[Marcin Zajączkowski](https://github.com/szpak),
[Alex Landau](https://github.com/AlexLandau),
[Stefan Oehme](https://github.com/oehme),
[yinghao niu](https://github.com/towith),
[Björn Kautler](https://github.com/Vampire),
[Tomasz Godzik](https://github.com/tgodzik),
[Kristian Kraljic](https://github.com/kristian),
[Matthew Haughton](https://github.com/3flex),
[Raphael Fuchs](https://github.com/REPLicated),
[Sebastian Schuberth](https://github.com/sschuberth),
[Roberto Perez Alcolea](https://github.com/rpalcolea),
[Xin Wang](https://github.com/scaventz)

>>>>>>> origin/release

## Upgrade instructions

Switch your build to use Gradle @version@ by updating your wrapper:

`./gradlew wrapper --gradle-version=@version@`

See the [Gradle 7.x upgrade guide](userguide/upgrading_version_7.html#changes_@baseVersion@) to learn about deprecations, breaking changes and other considerations when upgrading to Gradle @version@. 

For Java, Groovy, Kotlin and Android compatibility, see the [full compatibility notes](userguide/compatibility.html).

<<<<<<< HEAD
<!-- Do not add breaking changes or deprecations here! Add them to the upgrade guide instead. --> 

<!-- 

================== TEMPLATE ==============================

<a name="FILL-IN-KEY-AREA"></a>
### FILL-IN-KEY-AREA improvements

<<<FILL IN CONTEXT FOR KEY AREA>>>
Example:
> The [configuration cache](userguide/configuration_cache.html) improves build performance by caching the result of
> the configuration phase. Using the configuration cache, Gradle can skip the configuration phase entirely when
> nothing that affects the build configuration has changed.

#### FILL-IN-FEATURE
> HIGHLIGHT the usecase or existing problem the feature solves
> EXPLAIN how the new release addresses that problem or use case
> PROVIDE a screenshot or snippet illustrating the new feature, if applicable
> LINK to the full documentation for more details 

================== END TEMPLATE ==========================


==========================================================
ADD RELEASE FEATURES BELOW
vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv -->
=======
## New features and usability improvements

<a name="java17"></a>
### Support for Java 17

Gradle now supports running on and building with [Java 17](https://openjdk.java.net/projects/jdk/17/).

In previous Gradle versions, running Gradle itself on Java 17 resulted in an error. JVM projects could have been built with Java 17 [using toolchains](userguide/toolchains.html).

As of Gradle 7.3, both running Gradle itself and building JVM projects with Java 17 is fully supported.

<a name="test-suites"></a>
### Declarative test suites in JVM projects

When [testing Java & JVM projects](userguide/java_testing.html), you often need to group tests classes together to organize them into manageable chunks, so that you can run them with different frequencies or at distinct points in your build pipeline. For example, you may want to define groups of _unit tests_, _integration tests_, and _functional tests_.

Previously, grouping tests correctly required thorough knowledge of how to modify and connect various domain objects in Gradle, like SourceSets, configurations and tasks.  If you wanted to divide tests into different groups, you needed to understand how these separate parts interact with one another.

With Gradle 7.3, the [JVM Test Suite Plugin](userguide/jvm_test_suite_plugin.html) simplifies the creation of such groups of tests. We refer to these groups as **Test Suites**.  Note that this is not to be confused with testing framework suites, like [JUnit4’s Suite](https://junit.org/junit4/javadoc/4.13/org/junit/runners/Suite.html).

Test Suites are a high-level concept which can be referred to directly and consistently in build scripts. You can configure dependencies, sources and the testing framework used by the tests without having to worry about the low-level details.

For example, you can create an _integration testing_, test suite by adding the following snippet to a Java project:

```
testing {
    suites {
        // Add a new test suite
        integrationTest(JvmTestSuite) {
            // Use JUnit Jupiter as a testing framework
            useJUnitJupiter('5.7.1')

            // depend on the production code for tests
            dependencies {
                implementation project
            }
        }
    }
}

// Run integration tests as part of check
tasks.named('check') {
    dependsOn(testing.suites.integrationTest)
}
```

This functionality is available automatically for all JVM-based projects that apply the `java` plugin. The built-in `test` task has been re-implemented on top of test suites. See more in the [user manual](userguide/jvm_test_suite_plugin.html).

This API is [incubating](userguide/feature_lifecycle.html) and will likely change in future releases as more functionality is added.

<a name="scala"></a>
### Scala 3 support

The Scala plugin allows users to compile their Scala code using Gradle and the Zinc incremental compiler underneath.

The Scala plugin is now able to compile Scala 3 code. All existing configuration options should still be usable with the newest language version.

The newest version of Scala 3 brings about numerous features while keeping compatibility with most of the existing Scala 2 code. To see more about the language features go to [overview of the new features in Scala 3](https://docs.scala-lang.org/scala3/new-in-scala3.html).

### Explore new behavior with `gradle init`

When you initialize a new Gradle project using [`gradle init`](userguide/build_init_plugin.html#build_init_plugin), Gradle will now ask if you want to try new but unstable features in the build. This will allow you to try out new features before they become stable. You can always ask for this behavior by running `gradle init --incubating` when generating a new project.

Currently, builds generated with this option will only enable [Test Suites](#test-suites), but other new APIs or behaviors may be added as they are introduced.

### Version catalog improvements

### Allow copying single files into directories which contain unreadable files.
>>>>>>> origin/release



<<<<<<< HEAD
<!-- ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
ADD RELEASE FEATURES ABOVE
==========================================================

-->
=======
### More robust file system watching

When running an incremental build, Gradle needs to understand what has changed since the previous build on the file system. To do this it relies on the operating system's [file system events](userguide/gradle_daemon.html#sec:daemon_watch_fs) whenever possible.

However, these file system events can be unreliable in some environments, which could cause Gradle to ignore some changes.

To prevent this, Gradle now detects the reliability of file system events and automatically disables the file system watching optimization in case of issues.

### Allow copying single files into directories which contain unreadable files.

Sometimes you want to copy files into a directory which contains unreadable files or which is not exclusively owned by the build. For example when you are deploying single files into application servers or installing executables.

Doing so may fail or be slow because Gradle tries to track all the content in the destination directory.

In order to work around such issues, the `Copy` task now has a method [`Copy.ignoreExistingContentInDestinationDir()`](dsl/org.gradle.api.tasks.Copy.html#org.gradle.api.tasks.Copy:ignoreExistingContentInDestinationDir()) that forces Gradle to ignore content in the destination directory.

This feature works by declaring [untracked](#untracked) outputs under the hood.

See the samples in the user manual about [Deploying single files into application servers](userguide/working_with_files.html#sec:copy_deploy) and [Installing executables](userguide/working_with_files.html#sec:install_executable).

### Input normalization support in configuration cache

The [input normalization](userguide/more_about_tasks.html#sec:configure_input_normalization) is now correctly tracked by the experimental [configuration cache](userguide/configuration_cache.html). Task up-to-date checks now consider normalization rules when the configuration cache is enabled, leading to faster builds.

## Plugin development improvements

<a name="untracked"></a>
### Allow plugin authors to declare inputs or outputs as untracked

For up-to-date checks and the build cache, Gradle needs to track the state of the inputs and outputs of a task. It is not always desirable or possible for Gradle to fully track the state of the input and output files.

For example:
- The location contains unreadable files like pipes where Gradle cannot track the content.
- Another tool like Git already takes care of keeping the state, so it doesn't make sense for Gradle to do additional bookkeeping.
- The build does not own the output location exclusively and Gradle would need to track the state of a potentially large amount of content.

Gradle 7.3 introduces the annotation [`@Untracked`](javadoc/org/gradle/api/tasks/Untracked.html) and the method [TaskFilePropertyBuilder.untracked()](javadoc/org/gradle/api/tasks/TaskFilePropertyBuilder.html##untracked--) to declare that Gradle should not track the state of the input or output property.
This allows tasks to implement the above use-cases.

If a task has any untracked properties, then Gradle does not do any optimizations when running the task.
For example, such a task will always be out of date and never come from the build cache.

See the samples in the user manual about [Integrating an external tool which does its own up-to-date checking](userguide/more_about_tasks.html#sec:untracked_external_tool).
>>>>>>> origin/release

Initializing new plugin projects using the [Build Init Plugin](userguide/build_init_plugin.html#build_init_plugin) can also benefit from [the `--incubating` option](#explore-new-behavior-with-gradle-init).

<a name="tooling-api"></a>
## Improvements for tooling providers

The Tooling API allows applications to embed Gradle. This API is used by IDEs such as IDEA, Android Studio
and Buildship to integrate Gradle into the IDE.

### File download progress events

When a build downloads many files or very large files, for example when resolving dependencies, Gradle may appear to be unresponsive due to the lack of any logging or console output.

This release adds new events that notify the IDE as files are downloaded. This allows IDEs to show better progress information while Gradle is running and during IDE import/sync.

## Promoted features
Promoted features are features that were incubating in previous versions of Gradle but are now supported and subject to backwards compatibility.
See the User Manual section on the “[Feature Lifecycle](userguide/feature_lifecycle.html)” for more information.

The following are the features that have been promoted in this Gradle release.

<!--
### Example promoted
-->

## Fixed issues

## Known issues

Known issues are problems that were discovered post release that are directly related to changes made in this release.

## External contributions

We love getting contributions from the Gradle community. For information on contributing, please see [gradle.org/contribute](https://gradle.org/contribute).

## Reporting problems

If you find a problem with this release, please file a bug on [GitHub Issues](https://github.com/gradle/gradle/issues) adhering to our issue guidelines. 
If you're not sure you're encountering a bug, please use the [forum](https://discuss.gradle.org/c/help-discuss).

We hope you will build happiness with Gradle, and we look forward to your feedback via [Twitter](https://twitter.com/gradle) or on [GitHub](https://github.com/gradle).
