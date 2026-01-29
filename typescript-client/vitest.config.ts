import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Test environment
    environment: 'node',
    
    // Test file patterns
    include: ['tests/**/*.test.ts'],
    
    // Coverage configuration
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      include: ['src/**/*.ts'],
      exclude: [
        'src/**/*.test.ts',
        'src/**/index.ts', // Re-export file
      ],
      thresholds: {
        lines: 80,
        branches: 80,
        functions: 80,
        statements: 80,
      },
    },
    
    // Watch mode settings
    watch: false,
    
    // Global test timeout
    testTimeout: 10000,
    
    // Teardown timeout for async cleanup (ZeroMQ resources)
    teardownTimeout: 5000,
    
    // Hook timeout (for afterEach cleanup)
    hookTimeout: 15000,
    
    // Run tests in parallel for speed
    // Note: ZeroMQ native bindings may cause cleanup warnings but tests pass
    pool: 'threads',
    maxConcurrency: 5,
  },
});

