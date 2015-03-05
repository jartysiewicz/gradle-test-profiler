package com.blogspot.toomuchcoding.testprofiler
import com.blogspot.toomuchcoding.testprofiler.TestProfilerPluginExtension.BuildBreakerOptions
import com.blogspot.toomuchcoding.testprofiler.spock.CustomTimeout
import com.blogspot.toomuchcoding.testprofiler.spock.GlobalTimeoutExtension
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j
import javassist.ClassClassPath
import javassist.ClassPool
import javassist.CtClass
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test

@PackageScope
@CompileStatic
@Slf4j
class AfterCompilationTestTaskModifier extends DefaultTask {

    TestProfilerPluginExtension testProfilerPluginExtension
    @OutputDirectory File outputDir

    @TaskAction
    void testsProfileSummaryReport() {
        Long maxThreshold = getTestProfilerPluginExtension().buildBreakerOptions.maxTestThreshold
        if (maxThreshold == null) {
            log.info("No max test threshold has been provided thus no global timeout will be " +
                    "applied for project [$project.name]. Provided threshold was [$maxThreshold]")
            return
        }
        log.info("Adding global Timeout rule for project [$project.name]")
        this.project.plugins.withType(JavaPlugin) {
            this.project.tasks.withType(Test) { Task task ->
                Test testTask = (Test) task
                appendTimeoutRule(getTestProfilerPluginExtension().buildBreakerOptions, testTask)
                addGlobalExtensionToSpock(testTask, maxThreshold)
            }
        }
    }

    private void appendTimeoutRule(BuildBreakerOptions buildBreakerOptions, Test testTask) {
        new TestClassesModifer(project, buildBreakerOptions).appendRule(testTask)
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private void addGlobalExtensionToSpock(Test test, Long maxThreshold) {
        if (!project.plugins.findPlugin('groovy')) {
            log.debug("The project doesn't have Groovy plugin - skipping Spock extension adding")
            return
        }
        if (!test.classpath.files.any { it.absolutePath.contains('spock') }) {
            log.debug("Spock is not on classpath - skipping Spock extension adding")
            return
        }
        addEntryInMetaInf()
        addCompiledGlobalExtensionToTests(test)
        test.jvmArgs("-D${TestProfilerPlugin.DEFAULT_TEST_TIMEOUT_PROPERTY}=${maxThreshold.toString()}")
    }

    private void addCompiledGlobalExtensionToTests(Test test) {
        ClassPool pool = ClassPool.getDefault()
        pool.insertClassPath(new ClassClassPath(GlobalTimeoutExtension))
        pool.insertClassPath(new ClassClassPath(CustomTimeout))
        writeClass(GlobalTimeoutExtension, pool, test)
        writeClass(CustomTimeout, pool, test)
    }

    private void writeClass(Class clazz, ClassPool pool, Test test) {
        CtClass globalExtension = pool.get(clazz.name)
        globalExtension.writeFile(test.testClassesDir.absolutePath)
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private void addEntryInMetaInf() {
        File spockServices = new File(project.sourceSets.main.output.resourcesDir, '/META-INF/services')
        log.debug("Will create a meta-inf entry in [$spockServices]")
        spockServices.mkdirs()
        File globalExtensions = new File(spockServices, 'org.spockframework.runtime.extension.IGlobalExtension')
        if (globalExtensions.exists()) {
            globalExtensions.append("${System.getProperty("line.separator")}$GlobalTimeoutExtension.name")
        } else {
            globalExtensions.text = GlobalTimeoutExtension.name
        }
    }
}
