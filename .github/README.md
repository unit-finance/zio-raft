# GitHub Configuration

This directory contains GitHub-specific configuration files for the zio-raft repository.

## Files

### `copilot-instructions.md`

Custom instructions for GitHub Copilot that ensure quality checks (formatting, linting, testing) are performed before completing any task in this repository.

These instructions are automatically picked up by GitHub Copilot when working on pull requests and issues in this repository.

### `workflows/`

GitHub Actions workflow definitions:
- `scala-ci.yml` - CI checks for Scala code
- `typescript-ci.yml` - CI checks for TypeScript code  
- `copilot-setup-steps.yml` - Setup steps for GitHub Copilot workspace
- `publish.yml` - Publishing workflow

## Adding New Quality Checks

If you add new quality check commands (formatters, linters, etc.), please update `copilot-instructions.md` to ensure GitHub Copilot knows to run them.
