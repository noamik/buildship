package org.eclipse.buildship.core.workspace.internal

import groovy.lang.Closure
import org.gradle.api.JavaVersion
import spock.lang.IgnoreIf
import spock.lang.Issue

import com.gradleware.tooling.toolingclient.GradleDistribution

import org.eclipse.core.runtime.Path
import org.eclipse.debug.core.ILaunchConfiguration
import org.eclipse.jdt.core.IClasspathEntry
import org.eclipse.jdt.core.IJavaProject
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.launching.IRuntimeClasspathEntry
import org.eclipse.jdt.launching.JavaRuntime

import org.eclipse.buildship.core.test.fixtures.ProjectSynchronizationSpecification

class RuntimeClasspathTest extends ProjectSynchronizationSpecification {

    File location
    File buildFile

    def setup() {
        location = dir('sample-project') {
            file('settings.gradle') << "include 'a', 'b', 'c'"
            dir('a/src/main/java').mkdirs()
            dir('b/src/main/java').mkdirs()
            dir('c/src/main/java').mkdirs()
            buildFile = file 'build.gradle', '''
                subprojects {
                    apply plugin: 'java'

                    repositories {
                        mavenCentral()
                    }
                }
            '''
        }
    }

    def "Project classpath has entries only from local Gradle classpath container"() {
        setup:
        buildFile << '''
            project(':a') {
                dependencies {
                    compile 'log4j:log4j:1.2.17'
                }
            }

            project(':b') {
                dependencies {
                    compile 'log4j:log4j:1.2.16'
                }
            }

            project(':c') {
                dependencies {
                    compile project(':a')
                    compile project(':b')
                    testCompile 'junit:junit:4.12'
                }
            }
        '''
        importAndWait(location)

        when:
        IJavaProject javaProject = JavaCore.create(findProject('c'))
        IRuntimeClasspathEntry[] classpath = projectRuntimeClasspath(javaProject)

        then:
        classpath.find { it.path.lastSegment() == 'log4j-1.2.17.jar' }
        !classpath.find { it.path.lastSegment() == 'log4j-1.2.16.jar' }
    }

    def "Dependencies of project dependencies are removed from the runtime classpath"() {
        setup:
        buildFile << '''
            project(':a') {
                dependencies {
                    compile 'org.springframework:spring-core:4.3.1.RELEASE'
                }
            }

            project(':b') {
                configurations.all {
                    exclude group: 'commons-logging'
                }

                dependencies {
                    compile project(':a')
                }
            }
        '''
        importAndWait(location)

        when:
        IJavaProject javaProject = JavaCore.create(findProject('b'))
        IRuntimeClasspathEntry[] classpath = projectRuntimeClasspath(javaProject)

        then:
        !classpath.find { it.path.toPortableString().contains('commons-logging') }
    }

    @IgnoreIf({ JavaVersion.current().isJava9Compatible() }) // https://github.com/eclipse/buildship/issues/601
    def "Can access transitive dependencies resolved via project dependency"(GradleDistribution distribution) {
        setup:
        buildFile << '''
            project(':a') {
                dependencies {
                    compile 'com.google.guava:guava:18.0'
                }
            }

            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }

            project(':c') {
                dependencies {
                    compile project(':b')
                }
            }
        '''
        importAndWait(location, distribution)

        when:
        IRuntimeClasspathEntry[] classpathB = projectRuntimeClasspath(JavaCore.create(findProject('b')))
        IRuntimeClasspathEntry[] classpathC = projectRuntimeClasspath(JavaCore.create(findProject('c')))

        then:
        classpathB.find { it.path.toPortableString().contains('guava') }
        classpathC.find { it.path.toPortableString().contains('guava') }

        where:
        distribution << [ GradleDistribution.forVersion('2.4'), DEFAULT_DISTRIBUTION ]
    }

    def "Dependencies are still on the runtime classpath"() {
        setup:
        new File(location, 'b/lib').mkdirs()
        buildFile << '''
            project(':b') {
                dependencies {
                    compile project(':a')
                    compile 'log4j:log4j:1.2.17'
                    compile files('lib')
                }
            }
        '''
        importAndWait(location)

        when:
        IJavaProject javaProject = JavaCore.create(findProject('b'))
        IRuntimeClasspathEntry[] classpath = projectRuntimeClasspath(javaProject)

        then:
        classpath.length == 4
        classpath.find { it.type == IRuntimeClasspathEntry.PROJECT && it.path.lastSegment() == 'a' }
        classpath.find { it.type == IRuntimeClasspathEntry.PROJECT && it.path.lastSegment() == 'b' }
        classpath.find { it.type == IRuntimeClasspathEntry.ARCHIVE && it.path.lastSegment() == 'log4j-1.2.17.jar' }
        classpath.find { it.type == IRuntimeClasspathEntry.ARCHIVE && it.path.lastSegment() == 'lib' }
    }

    @Issue("https://bugs.eclipse.org/bugs/show_bug.cgi?id=507206")
    def "Runtime classpath contains custom output folders"() {
        setup:
        new File(location, 'a/src/main/java').mkdirs()
        buildFile << '''
            project(':a') {
                apply plugin: 'eclipse'

                eclipse {
                    classpath {
                        defaultOutputDir = file('default-output-dir')

                        file {
                            whenMerged {
                                def src = entries.find { it.path == 'src/main/java' }
                                src.output = 'custom-output-dir'
                            }
                        }
                    }
                }
            }

            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        '''
        importAndWait(location)

        when:
        IJavaProject javaProject = JavaCore.create(findProject('b'))
        IRuntimeClasspathEntry[] classpath = projectRuntimeClasspath(javaProject)

        then:
        classpath.find { it.type == IRuntimeClasspathEntry.ARCHIVE && it.path.lastSegment() == 'custom-output-dir' }
        classpath.find { it.type == IRuntimeClasspathEntry.ARCHIVE && it.path.lastSegment() == 'default-output-dir' }
    }

    def "Gradle classpath containers from different projects always considered unequal"() { //
        setup:
        File external = dir('external-gradle-project') {
            file('build.gradle') << """
                apply plugin: 'java'
                repositories.mavenCentral()
                dependencies.compile 'com.google.guava:guava:18.0'
            """
        }
        importAndWait(external)
        importAndWait(location)

        IJavaProject project = findJavaProject('a')
        project.setRawClasspath(project.rawClasspath + [JavaCore.newProjectEntry(new Path('/external-gradle-project'))] as IClasspathEntry[], null)
        IRuntimeClasspathEntry[] unresolvedClasspath = JavaRuntime.computeUnresolvedRuntimeClasspath(project)
        IRuntimeClasspathEntry[] resolvedClasspath = JavaRuntime.resolveRuntimeClasspath(unresolvedClasspath, createGradleLaunchConfig())

        expect:
        resolvedClasspath.find { it.path.lastSegment().contains 'guava' }
    }

    private IRuntimeClasspathEntry[] projectRuntimeClasspath(IJavaProject project) {
        IRuntimeClasspathEntry projectEntry = JavaRuntime.computeUnresolvedRuntimeClasspath(project).find { it.path == project.project.fullPath }
        JavaRuntime.resolveRuntimeClasspathEntry(projectEntry, project)
    }
}
