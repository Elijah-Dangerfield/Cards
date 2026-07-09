#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

import java.io.File
import java.util.*

// Color codes for terminal output
private val RED = "\u001b[31m"
private val GREEN = "\u001b[32m"
private val YELLOW = "\u001b[33m"
private val BLUE = "\u001b[34m"
private val RESET = "\u001b[0m"

fun printRed(text: String) = println("$RED$text$RESET")
fun printGreen(text: String) = println("$GREEN$text$RESET")
fun printYellow(text: String) = println("$YELLOW$text$RESET")
fun printBlue(text: String) = println("$BLUE$text$RESET")

data class ModuleConfig(
    val fullName: String,
    val type: ModuleType,
    val hasImpl: Boolean = false
) {
    val parentModule: String? = fullName.split(":").takeIf { it.size > 1 }?.get(0)
    val moduleName: String = fullName.split(":").last()
    val baseDir: String = when (type) {
        ModuleType.FEATURE -> "features"
        ModuleType.LIBRARY -> "libraries"
    }
    val isSubModule: Boolean = fullName.contains(":")
    val capitalizedName: String = if (moduleName == "impl" && parentModule != null) {
        parentModule.replaceFirstChar { it.uppercase() }
    } else {
        moduleName.replaceFirstChar { it.uppercase() }
    }

    fun isStorageSubModule(): Boolean = isSubModule && moduleName == "storage"

    fun projectsAccessor(): String = "projects.${baseDir}.${fullName.replace(":", "." )}"

    fun shouldDependOnStorageApi(): Boolean = type == ModuleType.LIBRARY && isStorageSubModule()
}

enum class ModuleType(val displayName: String) {
    FEATURE("feature"),
    LIBRARY("library")
}

fun main() {
    printBlue("🚀 Cards Module Creator")
    println()
    
    if (checkForHelp()) return
    
    val moduleConfig = gatherModuleInfo() ?: return
    
    try {
        createModule(moduleConfig)
        
        if (moduleConfig.hasImpl) {
            val implConfig = moduleConfig.copy(
                fullName = "${moduleConfig.fullName}:impl",
                hasImpl = false
            )
            createModule(implConfig)
        }
        
        printGreen("✅ Success! Module '${moduleConfig.fullName}' created successfully")
        if (moduleConfig.hasImpl) {
            printGreen("✅ Implementation module '${moduleConfig.fullName}:impl' created successfully")
        }
        
        printYellow("📝 Next steps:")
        println("   1. Sync Gradle files")
        println("   2. Update README.md with module documentation")
        println("   3. Add module dependencies as needed")
        
    } catch (e: Exception) {
        printRed("❌ Error creating module: ${e.message}")
        e.printStackTrace()
    }
}

fun checkForHelp(): Boolean {
    val isHelpRequest = args.isNotEmpty() && (args[0] == "-h" || args[0] == "--help" || args[0] == "help")
    
    if (isHelpRequest) {
        printBlue("""
            Cards Module Creator
            
            This script creates new KMP modules for the Cards project with proper structure and configuration.
            
            Usage: ./create_module.main.kts [module-type] [module-name]
            
            Arguments:
              module-type    Optional: "feature" or "library"
              module-name    Optional: camelCase name (use "parent:child" for sub-modules)
            
            Examples:
              ./create_module.main.kts feature messaging
              ./create_module.main.kts library analytics
              ./create_module.main.kts library user:preferences
            
            Module Types:
              feature    - UI-containing modules with screens and navigation
              library    - Pure Kotlin logic, utilities, data handling
            
            Features:
              ✅ Kotlin Multiplatform structure (commonMain, androidMain, iosMain, jvmMain)
              ✅ Proper build.gradle.kts with correct plugins
              ✅ Package structure with base files
              ✅ Settings.gradle.kts auto-update
              ✅ App module dependency auto-update
              ✅ Public/implementation module pattern support
        """.trimIndent())
    }
    
    return isHelpRequest
}

fun gatherModuleInfo(): ModuleConfig? {
    val moduleType = getModuleType() ?: return null
    val fullModuleName = getModuleName() ?: return null
    val hasImpl = shouldCreateImplModule(fullModuleName)
    
    return ModuleConfig(fullModuleName, moduleType, hasImpl)
}

fun getModuleType(): ModuleType? {
    val typeInput = args.getOrNull(0) ?: run {
        print("Enter module type (feature/library): ")
        readln().trim().lowercase()
    }
    
    return when (typeInput) {
        "feature", "f" -> ModuleType.FEATURE
        "library", "lib", "l" -> ModuleType.LIBRARY
        "quit", "q", "exit" -> {
            printYellow("👋 Goodbye!")
            null
        }
        else -> {
            printRed("❌ Invalid module type. Must be 'feature' or 'library'")
            null
        }
    }
}

fun getModuleName(): String? {
    val nameInput = args.getOrNull(1) ?: run {
        println("Enter module name (camelCase):")
        println("  • For sub-modules use: parentModule:subModule")
        println("  • Examples: messaging, user:preferences, notifications")
        print("Module name: ")
        readln().trim()
    }
    
    if (nameInput.isEmpty() || nameInput == "q" || nameInput == "quit") {
        printYellow("👋 Goodbye!")
        return null
    }
    
    if (!isValidModuleName(nameInput)) {
        printRed("❌ Invalid module name. Use camelCase and only alphanumeric characters")
        return null
    }
    
    return nameInput
}

fun isValidModuleName(name: String): Boolean {
    val parts = name.split(":")
    return parts.all { part ->
        part.isNotEmpty() && 
        part.first().isLetterOrDigit() && 
        part.all { it.isLetterOrDigit() }
    }
}

fun shouldCreateImplModule(fullName: String): Boolean {
    if (fullName.contains(":")) return false // Don't create impl for sub-modules
    
    print("Create implementation module? (Y/n): ")
    val response = readln().trim().lowercase()
    return response.isEmpty() || response == "y" || response == "yes"
}

fun createModule(config: ModuleConfig) {
    printBlue("📦 Creating ${config.type.displayName} module: ${config.fullName}")
    
    val moduleDir = createModuleDirectory(config)
    createSourceStructure(config, moduleDir)
    createBuildGradle(config, moduleDir)
    updateSettingsGradle(config)
    updateAppModule(config)
    updateStorageDependencies(config)
    
    printGreen("✅ Module structure created at: $moduleDir")
}

fun createModuleDirectory(config: ModuleConfig): File {
    val path = if (config.parentModule != null) {
        "${config.baseDir}/${config.parentModule}/${config.moduleName}"
    } else {
        "${config.baseDir}/${config.moduleName}"
    }
    
    val moduleDir = File(path)
    if (moduleDir.exists()) {
        throw IllegalStateException("Module directory already exists: $path")
    }
    
    moduleDir.mkdirs()
    return moduleDir
}

fun createSourceStructure(config: ModuleConfig, moduleDir: File) {
    val packagePath = "com.cards.${config.baseDir}.${config.fullName.replace(":", ".")}".replace(".", "/")
    
    // Create KMP source structure
    val sourceSets = listOf("commonMain", "androidMain", "iosMain", "jvmMain")
    
    sourceSets.forEach { sourceSet ->
        val kotlinDir = File(moduleDir, "src/$sourceSet/kotlin/$packagePath")
        kotlinDir.mkdirs()
        
        // Create a placeholder file for each source set
        if (sourceSet == "commonMain") {
            createMainSourceFile(config, kotlinDir)
            
            // Create composeResources directory for commonMain
            val composeResourcesDir = File(moduleDir, "src/$sourceSet/composeResources")
            composeResourcesDir.mkdirs()
        }
    }
    
    // Create test directories
    val testDir = File(moduleDir, "src/commonTest/kotlin/$packagePath")
    testDir.mkdirs()
}

fun createMainSourceFile(config: ModuleConfig, kotlinDir: File) {
    val packageName = "com.dangerfield.cards.${config.baseDir}.${config.fullName.replace(":", ".")}"
    
    when (config.type) {
        ModuleType.FEATURE -> {
            // For impl modules, create the entry point and view model
            // For non-impl or single modules, create the route
            if (config.moduleName == "impl") {
                createFeatureEntryPoint(config, kotlinDir, packageName)
                createFeatureViewModel(config, kotlinDir, packageName)
            } else if (!config.hasImpl) {
                // Single module gets route, entry point, and view model
                createFeatureRoute(config, kotlinDir, packageName)
                createFeatureEntryPoint(config, kotlinDir, packageName)
                createFeatureViewModel(config, kotlinDir, packageName)
            } else {
                // Non-impl module with impl gets just the route
                createFeatureRoute(config, kotlinDir, packageName)
            }
        }
        ModuleType.LIBRARY -> createLibraryFile(config, kotlinDir, packageName)
    }
}

fun createFeatureRoute(config: ModuleConfig, kotlinDir: File, packageName: String) {
    val routeFile = File(kotlinDir, "${config.capitalizedName}Route.kt")
    val routeContent = """
package $packageName

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

@Serializable
class ${config.capitalizedName}Route(): Route()
    """.trimIndent()
    
    routeFile.writeText(routeContent)
}

fun createFeatureEntryPoint(config: ModuleConfig, kotlinDir: File, packageName: String) {
    val entryPointFile = File(kotlinDir, "${config.capitalizedName}FeatureEntryPoint.kt")
    
    // Determine the correct route package and class name
    val (routePackage, routeClass) = if (config.moduleName == "impl" && config.parentModule != null) {
        val parentCapitalized = config.parentModule.replaceFirstChar { it.uppercase() }
        val parentPackage = "com.dangerfield.cards.${config.baseDir}.${config.parentModule}"
        Pair(parentPackage, "${parentCapitalized}Route")
    } else {
        Pair(packageName, "${config.capitalizedName}Route")
    }
    
    val routeImport = if (routePackage != packageName) {
        "import $routePackage.$routeClass\n"
    } else {
        ""
    }
    
    val entryPointContent = """
package $packageName

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
${routeImport}import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.text.Text
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class ${config.capitalizedName}FeatureEntryPoint : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<$routeClass> {
            ${config.capitalizedName}Screen()
        }
    }
}

@Composable
fun ${config.capitalizedName}Screen() {
    Screen(modifier = Modifier.fillMaxSize()) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            Text(text = "${config.capitalizedName} Screen")
        }
    }
}
    """.trimIndent()
    
    entryPointFile.writeText(entryPointContent)
}

fun createFeatureViewModel(config: ModuleConfig, kotlinDir: File, packageName: String) {
    val viewModelFile = File(kotlinDir, "${config.capitalizedName}ViewModel.kt")
    
    val viewModelContent = """
package $packageName

import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import kotlinx.coroutines.delay
import me.tatarka.inject.annotations.Inject

class ${config.capitalizedName}ViewModel @Inject constructor() : SEAViewModel<State, Event, Action>(initialStateArg = State()) {
    
    override suspend fun handleAction(action: Action) {
        when (action) {
            is Action.Load -> action.handle()
        }
    }
    
    private suspend fun Action.Load.handle() {
        delay(5000) // do some loading
        updateState {
            it.copy(isLoading = false)
        }
    }
}

data class State(
    val isLoading: Boolean = true
)

sealed class Event {
    data object NavigateToSomewhere : Event()
}

sealed class Action {
    data object Load : Action()
}
    """.trimIndent()
    
    viewModelFile.writeText(viewModelContent)
}

fun createLibraryFile(config: ModuleConfig, kotlinDir: File, packageName: String) {
    val fileName = if (config.moduleName == "impl") {
        "${config.parentModule?.replaceFirstChar { it.uppercase() } ?: ""}Impl.kt"
    } else {
        "${config.capitalizedName}.kt"
    }
    
    val libFile = File(kotlinDir, fileName)
    val libContent = """
package $packageName

/**
 * ${config.capitalizedName} library module
 * 
 * TODO: Add your library implementation here
 */
class ${config.capitalizedName}
    """.trimIndent()
    
    libFile.writeText(libContent)
}

fun createBuildGradle(config: ModuleConfig, moduleDir: File) {
    val buildFile = File(moduleDir, "build.gradle.kts")
    val pluginId = when (config.type) {
        ModuleType.FEATURE -> "cards.feature"
        ModuleType.LIBRARY -> "cards.kotlin.multiplatform"
    }
    
    val namespace = "com.dangerfield.cards.${config.baseDir}.${config.fullName.replace(":", ".")}"
    val storageConfiguration = if (config.shouldDependOnStorageApi()) "\nmoduleConfig.storage()\n" else ""
    
    val buildContent = """
plugins {
    id("$pluginId")
}

android {
    namespace = "$namespace"
}
$storageConfiguration

kotlin {
    sourceSets {
        commonMain.dependencies {
            ${getCommonDependencies(config)}
        }
    }
}
    """.trimIndent()
    
    buildFile.writeText(buildContent)
}

fun getCommonDependencies(config: ModuleConfig): String {
    val baseDependencies = when (config.type) {
        ModuleType.FEATURE -> """
            implementation(projects.libraries.core)
            implementation(projects.libraries.ui)
            implementation(projects.libraries.navigation)
            implementation(projects.libraries.flowroutines)

            // Compose dependencies (navigation and lifecycle provided by cards.feature plugin)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)"""

        ModuleType.LIBRARY -> """
            implementation(projects.libraries.core)
            implementation(projects.libraries.flowroutines)"""
    }

    // If this is an impl module, add dependency on the non-impl module
    val withParentDependency = if (config.moduleName == "impl" && config.parentModule != null) {
        val parentModuleDependency = "            implementation(projects.${config.baseDir}.${config.parentModule})"
        "$parentModuleDependency\n\n$baseDependencies"
    } else {
        baseDependencies
    }

    return if (config.shouldDependOnStorageApi()) {
        "$withParentDependency\n            implementation(projects.libraries.storage)"
    } else {
        withParentDependency
    }
}

fun updateSettingsGradle(config: ModuleConfig) {
    val settingsFile = File("settings.gradle.kts")
    if (!settingsFile.exists()) {
        throw IllegalStateException("settings.gradle.kts not found in root directory")
    }
    
    val lines = settingsFile.readLines().toMutableList()
    val includeStatement = "include(\":${config.baseDir}:${config.fullName}\")"
    
    if (lines.any { it.contains(includeStatement) }) {
        return // Already included
    }
    
    // Find the appropriate section to add the include
    val sectionComment = when (config.type) {
        ModuleType.FEATURE -> "// Features"
        ModuleType.LIBRARY -> "// Libraries"
    }
    
    val sectionIndex = lines.indexOfFirst { it.trim() == sectionComment }
    if (sectionIndex != -1) {
        // Find the last include in this section
        var insertIndex = sectionIndex + 1
        while (insertIndex < lines.size && lines[insertIndex].trim().startsWith("include(")) {
            insertIndex++
        }
        lines.add(insertIndex, includeStatement)
    } else {
        // If no section found, add at the end
        lines.add(includeStatement)
    }
    
    // Sort includes within each section
    sortIncludesInSection(lines, sectionComment)
    
    settingsFile.writeText(lines.joinToString("\n"))
    printGreen("✅ Updated settings.gradle.kts")
}

fun sortIncludesInSection(lines: MutableList<String>, sectionComment: String) {
    val sectionIndex = lines.indexOfFirst { it.trim() == sectionComment }
    if (sectionIndex == -1) return
    
    val includes = mutableListOf<String>()
    var currentIndex = sectionIndex + 1
    
    while (currentIndex < lines.size && lines[currentIndex].trim().startsWith("include(")) {
        includes.add(lines.removeAt(currentIndex))
    }
    
    includes.sort()
    lines.addAll(sectionIndex + 1, includes)
}

fun updateAppModule(config: ModuleConfig) {
    val appBuildFile = File("apps/compose/build.gradle.kts")
    if (!appBuildFile.exists()) {
        printYellow("⚠️  Could not find app module build.gradle.kts - skipping dependency addition")
        return
    }
    
    val lines = appBuildFile.readLines().toMutableList()
    val dependencyLine = "            implementation(projects.${config.baseDir}.${config.fullName.replace(":", ".")})"
    
    if (lines.any { it.contains(dependencyLine.trim()) }) {
        return // Already added
    }
    
    // Find commonMain.dependencies block
    val commonMainIndex = lines.indexOfFirst { it.contains("commonMain.dependencies") }
    if (commonMainIndex != -1) {
        var insertIndex = commonMainIndex + 1
        
        // Find the appropriate subsection
        val targetComment = when (config.type) {
            ModuleType.FEATURE -> "            implementation(projects.features"
            ModuleType.LIBRARY -> "            implementation(projects.libraries"
        }
        
        // Find where to insert (after existing similar dependencies)
        while (insertIndex < lines.size && !lines[insertIndex].contains("}")) {
            if (lines[insertIndex].contains(targetComment)) {
                // Found similar dependencies, find the last one
                while (insertIndex + 1 < lines.size && 
                       lines[insertIndex + 1].contains(targetComment)) {
                    insertIndex++
                }
                insertIndex++
                break
            }
            insertIndex++
        }
        
        lines.add(insertIndex, dependencyLine)
        appBuildFile.writeText(lines.joinToString("\n"))
        printGreen("✅ Updated app module dependencies")
    } else {
        printYellow("⚠️  Could not find commonMain.dependencies in app module - please add dependency manually:")
        printYellow("   $dependencyLine")
    }
}

fun updateStorageDependencies(config: ModuleConfig) {
    if (!config.isStorageSubModule()) return
    val parent = config.parentModule ?: return
    val storageDependency = config.projectsAccessor()

    val parentImplBuildFile = File("${config.baseDir}/$parent/impl/build.gradle.kts")
    addCommonMainDependency(parentImplBuildFile, storageDependency)

    val librariesStorageImpl = File("libraries/storage/impl/build.gradle.kts")
    addCommonMainDependency(librariesStorageImpl, storageDependency)
}

fun addCommonMainDependency(targetFile: File, dependencyProject: String) {
    if (!targetFile.exists()) {
        printYellow("⚠️  Could not find ${targetFile.path} - please add dependency manually: implementation($dependencyProject)")
        return
    }

    val dependencyLine = "            implementation($dependencyProject)"
    val lines = targetFile.readLines().toMutableList()

    if (lines.any { it.trim() == dependencyLine.trim() }) {
        return
    }

    val commonMainIndex = lines.indexOfFirst { it.contains("commonMain.dependencies") }
    if (commonMainIndex == -1) {
        printYellow("⚠️  Could not find commonMain.dependencies in ${targetFile.path} - please add dependency manually: $dependencyLine")
        return
    }

    var insertIndex = commonMainIndex + 1
    while (insertIndex < lines.size) {
        if (lines[insertIndex].contains("}")) {
            break
        }
        insertIndex++
    }

    lines.add(insertIndex, dependencyLine)
    targetFile.writeText(lines.joinToString("\n"))
    printGreen("✅ Updated ${targetFile.path} with $dependencyProject")
}

// Run the script
main()