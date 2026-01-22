# Contributing to RokidStream

Thank you for your interest in contributing to RokidStream! This document provides guidelines and information about contributing to this project.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [How to Contribute](#how-to-contribute)
- [Development Setup](#development-setup)
- [Coding Standards](#coding-standards)
- [Pull Request Process](#pull-request-process)
- [Issue Guidelines](#issue-guidelines)

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment. Please:

- Be respectful of differing viewpoints and experiences
- Accept constructive criticism gracefully
- Focus on what is best for the community
- Show empathy towards other community members

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/yourusername/RokidStream.git
   cd RokidStream
   ```
3. **Add the upstream remote**:
   ```bash
   git remote add upstream https://github.com/originalowner/RokidStream.git
   ```
4. **Create a branch** for your changes:
   ```bash
   git checkout -b feature/your-feature-name
   ```

## How to Contribute

### Reporting Bugs

Before submitting a bug report:
- Check the [existing issues](https://github.com/yourusername/RokidStream/issues) to avoid duplicates
- Collect relevant information:
  - Android version on both devices
  - Device models (especially for AR glasses)
  - Logcat output from both sender and receiver
  - Steps to reproduce the issue

Submit a bug report with:
- A clear, descriptive title
- Detailed steps to reproduce
- Expected vs actual behavior
- Relevant logs and screenshots

### Suggesting Features

Feature suggestions are welcome! Please:
- Check existing issues for similar suggestions
- Describe the use case and benefits
- Consider implementation complexity
- Be open to discussion and feedback

### Code Contributions

We accept contributions for:
- Bug fixes
- Performance improvements
- New features (discuss first in an issue)
- Documentation improvements
- Test coverage

## Development Setup

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17 or higher
- Android SDK 34
- Two Android devices (one with camera, one for display)

### Building the Project

1. Open the project in Android Studio
2. Sync Gradle files
3. Build both modules:
   ```bash
   ./gradlew build
   ```

### Running Tests

```bash
# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device)
./gradlew connectedAndroidTest
```

### Debugging

Use the following logcat filters:
```bash
# Sender logs
adb logcat -s RokidSender:D

# Receiver logs
adb logcat -s RokidReceiver:D

# Both
adb logcat -s RokidSender:D RokidReceiver:D
```

## Coding Standards

### Kotlin Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Keep functions small and focused
- Add KDoc comments for public APIs

### Code Organization

```kotlin
// Import order: Android, Kotlin standard library, third-party, project
import android.bluetooth.*
import kotlin.collections.*
import org.lz4.*
import com.rokid.stream.sender.*
```

### Commit Messages

Use clear, descriptive commit messages:
```
<type>(<scope>): <subject>

<body>

<footer>
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation
- `style`: Code style (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding tests
- `chore`: Maintenance tasks

Example:
```
feat(sender): add frame rate configuration option

Allow users to configure the target frame rate via a settings UI.
Default remains 10 FPS for BLE compatibility.

Closes #42
```

### Documentation

- Update README.md for user-facing changes
- Add inline comments for complex logic
- Include KDoc for public functions and classes

## Pull Request Process

1. **Update your branch** with the latest upstream changes:
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

2. **Ensure all tests pass**:
   ```bash
   ./gradlew check
   ```

3. **Push your branch**:
   ```bash
   git push origin feature/your-feature-name
   ```

4. **Create a Pull Request** on GitHub with:
   - Clear description of changes
   - Reference to related issues
   - Screenshots/videos if UI changes
   - Testing steps for reviewers

5. **Address review feedback** promptly

6. **Merge** after approval (maintainer will merge)

### PR Checklist

- [ ] Code follows project style guidelines
- [ ] Self-review completed
- [ ] Comments added for complex code
- [ ] Documentation updated
- [ ] Tests added/updated
- [ ] All tests pass
- [ ] No new warnings introduced

## Issue Guidelines

### Bug Reports

Include:
- **Environment**: Android versions, device models
- **Steps to reproduce**: Numbered list
- **Expected behavior**: What should happen
- **Actual behavior**: What actually happens
- **Logs**: Relevant logcat output
- **Screenshots/Videos**: If applicable

### Feature Requests

Include:
- **Use case**: Why this feature is needed
- **Proposed solution**: Your suggested approach
- **Alternatives**: Other solutions considered
- **Additional context**: Any relevant information

## Questions?

Feel free to:
- Open a [Discussion](https://github.com/yourusername/RokidStream/discussions) for questions
- Reach out to maintainers via issues

Thank you for contributing! 🎉
