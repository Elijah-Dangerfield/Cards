# Cards Scripts

This directory contains utility scripts for the Cards project.

## init_project.main.kts

**Run this first!** This script initializes your project from the template by renaming all placeholder names to your chosen project name.

### Usage

```bash
cd scripts
./init_project.main.kts
```

The script will prompt you for:
- **App Name** (e.g., "My Awesome App") - used for display names
- **Package Name** (e.g., "com.example.myapp") - used for package declarations

It handles all naming conventions automatically:
- `PascalCase` → MyAwesomeApp
- `camelCase` → myAwesomeApp
- `kebab-case` → my-awesome-app
- `snake_case` → my_awesome_app
- `lowercase` → myawesomeapp

---

## create_module.main.kts

An intelligent Kotlin script for creating new KMP modules with proper structure and configuration.

### Features

✅ **Kotlin Multiplatform Structure**: Creates proper KMP module structure with `commonMain`, `androidMain`, `iosMain`, and `jvmMain` source sets

✅ **Smart Plugin Selection**: Automatically applies correct build plugins:
- `cards.feature` for UI feature modules
- `cards.kotlin.multiplatform` for pure Kotlin libraries

✅ **Package Structure**: Creates proper package hierarchy following `com.dangerfield.cards.{type}.{module}`

✅ **Auto-Generated Files**: Creates starter files appropriate for module type:
- Feature modules get Screen classes with Compose UI and ViewModel
- Library modules get basic Kotlin classes

✅ **Dependency Management**: Automatically updates:
- `settings.gradle.kts` with module includes
- `apps/compose/build.gradle.kts` with project dependencies

✅ **Implementation Module Pattern**: Supports public/impl module pattern for libraries

### Usage

#### Interactive Mode

```bash
cd scripts
./create_module.main.kts
```

#### Command Line Arguments

```bash
./create_module.main.kts [module-type] [module-name]
```

### Examples

#### Create a Feature Module

```bash
./create_module.main.kts feature messaging
```

#### Create a Library Module

```bash
./create_module.main.kts library analytics
```

#### Create a Sub-Module

```bash
./create_module.main.kts library user:preferences
```

---

## rotate_apple_sign_in_token.main.kts

Utility script for rotating Apple Sign In tokens. Used for authentication setup.

---

## cleanup.sh

Script for cleaning build artifacts and caches.

```bash
./scripts/cleanup.sh
```
