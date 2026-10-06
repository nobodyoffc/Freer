# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Freer (formerly Safe) is an Android cryptographic wallet application focused on privacy and security. It provides comprehensive tools for private key management, cryptocurrency transactions, multi-signature operations, and cryptographic functions.

## Build System

This is a multi-module Android project using Gradle:

### Key Commands
- **Build project**: `./gradlew build`
- **Build debug APK**: `./gradlew assembleDebug`
- **Build release APK**: `./sign-release.sh` → `build/release/Freer-<version>.apk`. Never ship Gradle's own `app-release.apk`: it lacks the v3 rotation proof (lineage) from the signer of 3.3.0 and earlier, so every existing install would refuse it as an update. See the header of `sign-release.sh`.
- **Run tests**: `./gradlew test`
- **Run instrumented tests**: `./gradlew connectedAndroidTest`
- **Clean project**: `./gradlew clean`
- **Install debug build**: `./gradlew installDebug`

### Module Structure
- **app**: Main Android application module
- **FC-AJDK**: Core library module containing cryptographic functionality and blockchain operations

## Architecture Overview

### Core Application Structure
The app follows a modular architecture with clear separation of concerns:

**Application Layer**:
- `FreerApplication`: Application class handling global state, lifecycle management, and service configuration
- `MainActivity`: Entry point that handles authentication flow and application initialization

**Manager Layer**:
- `ConfigureManager`: Manages application configuration and encryption keys
- `SettingManager`: Handles user settings and preferences per identity
- `DatabaseManager`: Manages database operations and lifecycle
- `CashManager`, `SecretManager`, `KeyInfoManager`, `MultisignManager`: Domain-specific managers

**Network Layer**:
- `ClientGroup`: Manages multiple API clients with different strategies (first available, round-robin, etc.)
- `ApipClient`: APIP service client with automatic service discovery
- API architecture supports multiple service types (APIP, DISK) with flexible client management

**UI Structure**:
- Feature-based packages: `home`, `myKeys`, `secret`, `multisig`, `tx`, `tools`, `qr`
- Common UI components in `ui` package
- Activity-specific dialogs and custom views

### Key Architecture Patterns

**Identity Management**:
- Each user identity (CID/FID) has its own `Setting` instance
- Settings are managed per-identity and contain client configurations and manager instances
- Authentication flow: Password → CID Selection → Setting Creation → Module Loading

**API Client Management**:
- `ClientGroup` manages multiple clients for the same service type
- Supports strategies: USE_FIRST, USE_ANY_VALID, USE_ALL, USE_ONE_RANDOM, USE_ONE_ROUND_ROBIN
- Automatic service discovery for free APIs
- Ping validation for service availability

**Data Serialization**:
- All FcEntity objects must be serialized to JSON before transmission, storage, or passing between components
- Use `fcEntity.toJson()` for serialization and `FcEntity.fromJson(json, Class)` for deserialization

## Development Guidelines

### UI Conventions
- **Buttons**: Apply `@style/ButtonStyle` for consistent styling
- **Toolbars**: Use `ToolbarUtils.setupToolbar(this, "Activity Title")` in onCreate after setContentView
- **Layout**: Include `@layout/common_toolbar` as first element in activity layouts (except HomeActivity and MainActivity)
- **Text Resources**: Define all user-visible text in `strings.xml`

### Code Standards
- **FcEntity Serialization**: Always convert FcEntity objects to JSON for Intent extras, database storage, network requests, and file storage
- **Logging**: Use `TimberLogger` for consistent logging across the application
- **Error Handling**: Use proper exception handling with user-friendly error messages
- **Database Operations**: Use appropriate managers for domain-specific database operations

### Testing
- Unit tests in `src/test/java`
- Instrumented tests in `src/androidTest/java`
- API testing utilities in manager packages

## Key Dependencies

### Main Dependencies
- **AndroidX**: Core Android libraries (AppCompat, Material Design, CameraX)
- **Hawk**: Secure key-value storage
- **Timber**: Logging framework
- **ZXing**: QR code scanning and generation
- **Gson**: JSON serialization
- **freecashj**: Blockchain operations library

### FC-AJDK Module Dependencies
- **Retrofit/OkHttp**: Network operations
- **Jackson**: Additional JSON processing
- **SQLCipher**: Encrypted database
- **Kotlin Coroutines**: Asynchronous operations

## Configuration

### Application Configuration
- Minimum SDK: 28 (Android 9.0)
- Target SDK: 34 (Android 14)
- Java 17 toolchain
- Service types and quantities configured in `FreerApplication.serviceNumberMap`

### Free API Endpoints
Default APIP endpoints configured in `ApipClient.freeAPIs`:
- https://apip.cash/APIP
- https://freecash.info/APIP
- https://freer.cash/APIP
- https://help.cash/APIP

## Important Notes

### Security Considerations
- This is a cryptographic wallet application - handle private keys and sensitive data with extreme care
- All cryptographic operations should use the established patterns in FC-AJDK
- Never log sensitive information like private keys or passwords
- Always validate user input for cryptographic operations

### Development Workflow
- The app uses a complex initialization flow requiring password verification and identity selection
- Test with multiple identities to ensure proper isolation
- API client functionality requires network connectivity for service discovery
- Cryptographic functions can operate offline but may require specific data formats

### Recent Refactoring
The project recently underwent package renaming from `com.fc.safe` to `com.fc.freer`, evidenced by the git status showing many file moves. When working with existing code, be aware that documentation or comments may still reference the old package names.