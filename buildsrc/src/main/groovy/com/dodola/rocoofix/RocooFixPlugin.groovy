/*
 * Copyright (C) 2016 Baidu, Inc. All Rights Reserved.
 */
package com.dodola.rocoofix

import com.android.SdkConstants
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.transforms.ProGuardTransform
import com.dodola.rocoofix.utils.NuwaProcessor
import com.dodola.rocoofix.utils.NuwaSetUtils
import com.dodola.rocoofix.utils.RocooUtils
import com.google.common.collect.Sets
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.DomainObjectCollection
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.DefaultDomainObjectSet
import proguard.gradle.ProGuardTask

class RocooFixPlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "rocoo_fix";

    private static final String MAPPING_TXT = "mapping.txt"
    private static final String HASH_TXT = "hash.txt"
    public static RocooFixExtension rocooConfig
    public boolean showLog = false;

    @Override
    public void apply(Project project) {
        DefaultDomainObjectSet<ApplicationVariant> variants
        if (project.getPlugins().hasPlugin(AppPlugin)) {
            variants = project.android.applicationVariants;

            project.extensions.create(EXTENSION_NAME, RocooFixExtension);

            applyTask(project, variants);
        }
    }

    private void applyTask(Project project, DomainObjectCollection<BaseVariant> variants) {

        project.afterEvaluate {
            rocooConfig = RocooFixExtension.getConfig(project);

            def includePackage = rocooConfig.includePackage
            def excludeClass = rocooConfig.excludeClass
            if (rocooConfig.enable) {

                variants.all { variant ->  //遍历所有的变种，比如release和debug


                    if(!variant.getBuildType().isMinifyEnabled()){
                        println("不支持不开混淆的情况")
                        return;
                    }


                    //判断gradle的版本是1.4.0之前（有"dexTask"和"proguardTask"）还是之后->
                    // ->（之前的task都被取消了，ps:可以自定义transform,但是不好解决混淆的情况，transformClassesWithDexForDebug和transformClassesAndResourcesWithProguardForDebug之间插入task也是阔以滴）
                    def preDexTask = project.tasks.findByName(RocooUtils.getPreDexTaskName(project, variant))
                    //transformClassesWithDexForDebug
                    def dexTask = project.tasks.findByName(RocooUtils.getDexTaskName(project, variant))
                    //transformClassesAndResourcesWithProguardForDebug
                    def proguardTask = project.tasks.findByName(RocooUtils.getProGuardTaskName(project, variant))
//                    def processManifestTask = project.tasks.findByName(RocooUtils.getProcessManifestTaskName(project, variant))

                    //获取manifest文件
                    def manifestFile = variant.outputs.processManifest.manifestOutputFile[0]

                    //使用之前版本的混淆文件，并且将之前版本的各个类的md5值（hash.txt解析而得）用map的形式存储返回给hashMap
                    Map hashMap = applyMapping(project, variant, proguardTask)

                    //TODO 不知道和variant.name有什么区别
                    def dirName = variant.dirName

                    def rocooFixRootDir = new File("${project.projectDir}${File.separator}rocoofix${File.separator}version" + variant.getVersionCode())//project/rocoofix/version11
                    def outputDir = new File("${rocooFixRootDir}${File.separator}${dirName}")//project/rocoofix/version11/debug
                    def patchDir = new File("${outputDir}${File.separator}patch")//project/rocoofix/version11/debug/patch
                    def hashFile = new File(outputDir, "${HASH_TXT}")//project/rocoofix/version11/debug/hash.txt
//                    if(showLog) {
                    println("=========" + rocooFixRootDir);
                    println("=========" + outputDir);
                    println("=========" + hashFile);
                    println("=========" + patchDir);
                    println("==========" + variant.getVersionCode())
//                    }
                    if (!rocooFixRootDir.exists()) {
                        rocooFixRootDir.mkdirs();
                    }
                    if (!outputDir.exists()) {
                        outputDir.mkdirs();
                    }
//                    else {
//                        FileUtils.deleteDirectory(outputDir)
//                    }
                    if (!patchDir.exists()) {
                        patchDir.mkdirs();
                    }


                    //applyRocooDebugPatch -> 将改变的类转换成dex格式的jar包
                    def rocooPatchTaskName = "applyRocoo${variant.name.capitalize()}Patch"
                    project.task(rocooPatchTaskName) << {
                        if (patchDir) {
                            RocooUtils.makeDex(project, patchDir)
                        }
                    }
                    def rocooPatchTask = project.tasks[rocooPatchTaskName]

                    Closure prepareClosure = {//改闭包执行一些初始化操作
                        if (rocooConfig.excludeClass == null) {
                            rocooConfig.excludeClass = Sets.newHashSet();
                        }
                        def applicationClassName = RocooUtils.getApplication(manifestFile);
                        if (applicationClassName != null) {//排除Application类
                            applicationClassName = applicationClassName.replace(".", "/") + SdkConstants.DOT_CLASS
                            rocooConfig.excludeClass.add(applicationClassName)
                        }

                        if (rocooConfig.excludePackage == null) {
                            rocooConfig.excludePackage = Sets.newHashSet();
                        }
                        //排除android/support包
                        rocooConfig.excludePackage.add("android/support/")

                        outputDir.mkdirs()
                        if (!hashFile.exists()) {
                            hashFile.createNewFile()
                        } else {
                            hashFile.delete()
                            hashFile.createNewFile()
                        }
                    }

                    Closure copyMappingClosure = {

                        if (proguardTask) {
                            //混淆文件的保存位置
                            def mapFile = new File("${project.buildDir}${File.separator}outputs${File.separator}mapping${File.separator}${variant.dirName}${File.separator}mapping.txt")
                            if (mapFile.exists()) {
                                //将当前混淆文件copy到当前版本versionN文件夹下的mapping.txt文件中
                                def newMapFile = new File("${rocooFixRootDir}${File.separator}${dirName}${File.separator}mapping.txt");
                                FileUtils.copyFile(mapFile, newMapFile)
                            }
                        }
                    }


                    if (preDexTask) {
                        def rocooJarBeforePreDex = "rocooJarBeforePreDex${variant.name.capitalize()}"
                        project.task(rocooJarBeforePreDex) << {
                            def inputFiles = preDexTask.inputs.files.files

                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (NuwaProcessor.shouldProcessPreDexJar(path)) {
                                    NuwaProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }
                            }
                        }
                        def rocooJarBeforePreDexTask = project.tasks[rocooJarBeforePreDex]
                        rocooJarBeforePreDexTask.dependsOn preDexTask.taskDependencies.getDependencies(preDexTask)
                        preDexTask.dependsOn rocooJarBeforePreDexTask

                        rocooJarBeforePreDexTask.doFirst(prepareClosure)

                        def rocooClassBeforeDex = "rocooClassBeforeDex${variant.name.capitalize()}"
                        project.task(rocooClassBeforeDex) << {
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".class") && !path.contains("/R\$") && !path.endsWith("/R.class") && !path.endsWith("/BuildConfig.class")) {
                                    if (NuwaSetUtils.isIncluded(path, includePackage)) {
                                        if (!NuwaSetUtils.isExcluded(path, excludeClass)) {
                                            def bytes = NuwaProcessor.processClass(inputFile)
                                            path = path.split("${dirName}/")[1]
                                            def hash = DigestUtils.shaHex(bytes)
                                            hashFile.append(RocooUtils.format(path, hash))

                                            if (RocooUtils.notSame(hashMap, path, hash)) {
                                                def file = new File("${patchDir}/${path}")
                                                file.getParentFile().mkdirs()
                                                if (!file.exists()) {
                                                    file.createNewFile()
                                                }
                                                FileUtils.writeByteArrayToFile(file, bytes)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        def rocooClassBeforeDexTask = project.tasks[rocooClassBeforeDex]
                        rocooClassBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        rocooClassBeforeDexTask.doLast(copyMappingClosure)
                        rocooPatchTask.dependsOn rocooClassBeforeDexTask
                        dexTask.dependsOn rocooPatchTask
                    } else if (dexTask != null) {//此处代码应该注掉
                        def rocooJarBeforeDex = "rocooJarBeforeDex${variant.name.capitalize()}"
                        project.task(rocooJarBeforeDex) << {
                            //TODO　获取inputFiles(具体细节看的还不是很懂)
                            Set<File> inputFiles = RocooUtils.getDexTaskInputFiles(project, variant, dexTask)

                            inputFiles.each { inputFile ->

                                def path = inputFile.absolutePath
                                if (path.endsWith(SdkConstants.DOT_JAR)) {
                                    NuwaProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                } else if (inputFile.isDirectory()) {
                                    //不处理不开混淆的情况
                                    //intermediates/classes/debug
                                    def extensions = [SdkConstants.EXT_CLASS] as String[]

                                    def inputClasses = FileUtils.listFiles(inputFile, extensions, true);
                                    inputClasses.each {
                                        inputClassFile ->

                                            def classPath = inputClassFile.absolutePath
                                            if (classPath.endsWith(".class") && !classPath.contains("/R\$") && !classPath.endsWith("/R.class") && !classPath.endsWith("/BuildConfig.class")) {
                                                if (NuwaSetUtils.isIncluded(classPath, includePackage)) {
                                                    if (!NuwaSetUtils.isExcluded(classPath, excludeClass)) {
                                                        def bytes = NuwaProcessor.processClass(inputClassFile)


                                                        if("\\".equals(File.separator)){
                                                            classPath = classPath.split("${dirName}\\\\")[1]
                                                        }else{
                                                            classPath = classPath.split("${dirName}/")[1]
                                                        }

                                                        def hash = DigestUtils.shaHex(bytes)
                                                        hashFile.append(RocooUtils.format(classPath, hash))
                                                        if (RocooUtils.notSame(hashMap, classPath, hash)) {
                                                            def file = new File("${patchDir}${File.separator}${classPath}")
                                                            file.getParentFile().mkdirs()
                                                            if (!file.exists()) {
                                                                file.createNewFile()
                                                            }
                                                            FileUtils.writeByteArrayToFile(file, bytes)
                                                        }
                                                    }
                                                }
                                            }

                                    }
                                }
                            }
                        }
                        //:app:rocooJarBeforeDexDebug
                        def rocooJarBeforeDexTask = project.tasks[rocooJarBeforeDex]

                        rocooJarBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        rocooJarBeforeDexTask.doFirst(prepareClosure)
                        rocooJarBeforeDexTask.doLast(copyMappingClosure)
                        rocooPatchTask.dependsOn rocooJarBeforeDexTask
                        dexTask.dependsOn rocooPatchTask
                    }
                }
            }
        }
    }


    private static Map applyMapping(Project project, BaseVariant variant, Task proguardTask) {

        Map hashMap
        RocooFixExtension rocooConfig = RocooFixExtension.getConfig(project);
        if (rocooConfig.preVersionPath != null) {

            def preVersionPath = new File("${project.projectDir}${File.separator}rocoofix${File.separator}version" + rocooConfig.preVersionPath)
//project/rocoofix/version11

            if (preVersionPath.exists()) {
                def mappingFile = new File("${preVersionPath}${File.separator}${variant.dirName}${File.separator}${MAPPING_TXT}")
                if (mappingFile.exists()) {
                    if (proguardTask instanceof ProGuardTask) {
                        if (mappingFile.exists()) {
                            proguardTask.applymapping(mappingFile)
                        }
                    } else {//兼容gradle1.4 增加了transformapi
                        def manager = variant.variantData.getScope().transformManager;
                        def proguardTransform = manager.transforms.find {
                            it.class.name == ProGuardTransform.class.name
                        };
                        if (proguardTransform) {
                            proguardTransform.configuration.applyMapping = mappingFile
                        }
                    }
                }
            }
            if (preVersionPath.exists()) {
                def hashFile = new File("${preVersionPath}${File.separator}${variant.dirName}${File.separator}${HASH_TXT}")
                hashMap = RocooUtils.parseMap(hashFile)

            }
            return hashMap;
        }
    }

}
