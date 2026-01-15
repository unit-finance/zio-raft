import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      include: ['src/**/*.ts'],
      exclude: [
        'src/index.ts',       // CLI entry point
        'src/commands/*.ts',  // Command handlers (thin wrappers, tested via integration)
        'src/kvClient.ts',    // Wrapper around RaftClient (tested via mocks)
        'src/types.ts'        // Type definitions only
      ],
      thresholds: {
        lines: 90,
        branches: 90,
        functions: 90,
        statements: 90,
      },
    },
    include: ['tests/**/*.test.ts'],
  },
});
