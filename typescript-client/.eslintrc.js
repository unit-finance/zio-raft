module.exports = {
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 2022,
    sourceType: 'module',
    project: './tsconfig.eslint.json',
  },
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:@typescript-eslint/recommended-requiring-type-checking',
  ],
  plugins: ['@typescript-eslint'],
  env: {
    node: true,
    es2022: true,
  },
  globals: {
    vi: 'readonly',
    describe: 'readonly',
    it: 'readonly',
    expect: 'readonly',
    beforeEach: 'readonly',
    afterEach: 'readonly',
    beforeAll: 'readonly',
    afterAll: 'readonly',
  },
  rules: {
    // Warn on explicit any (allow for protocol/interop code)
    '@typescript-eslint/no-explicit-any': 'warn',
    
    // Allow common truthy patterns, but catch non-boolean in conditions
    '@typescript-eslint/strict-boolean-expressions': [
      'warn',
      {
        allowString: true,
        allowNumber: true,
        allowNullableObject: true,
        allowNullableBoolean: true,
        allowNullableString: true,
        allowNullableNumber: false,
        allowAny: false,
      },
    ],
    
    // Enforce consistent type definitions
    '@typescript-eslint/consistent-type-definitions': ['error', 'interface'],
    
    // Prefer readonly when possible
    '@typescript-eslint/prefer-readonly': 'warn',
    
    // Require explicit return types (warn only)
    '@typescript-eslint/explicit-function-return-type': [
      'warn',
      {
        allowExpressions: true,
        allowTypedFunctionExpressions: true,
        allowHigherOrderFunctions: true,
        allowDirectConstAssertionInArrowFunctions: true,
      },
    ],
    
    // No unused vars
    '@typescript-eslint/no-unused-vars': [
      'error',
      {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
      },
    ],
    
    // Enforce naming conventions
    '@typescript-eslint/naming-convention': [
      'error',
      {
        selector: 'interface',
        format: ['PascalCase'],
      },
      {
        selector: 'typeAlias',
        format: ['PascalCase'],
      },
      {
        selector: 'enum',
        format: ['PascalCase'],
      },
    ],
    
    // Allow async without await (common for interface consistency)
    '@typescript-eslint/require-await': 'off',
    
    // Allow enum comparisons (common in protocol code)
    '@typescript-eslint/no-unsafe-enum-comparison': 'off',
    
    // Downgrade unsafe-* to warnings for protocol interop
    '@typescript-eslint/no-unsafe-assignment': 'warn',
    '@typescript-eslint/no-unsafe-member-access': 'warn',
    '@typescript-eslint/no-unsafe-call': 'warn',
    '@typescript-eslint/no-unsafe-argument': 'warn',
    
    // Allow case declarations (common pattern)
    'no-case-declarations': 'off',
    
    // Handle misused promises (important)
    '@typescript-eslint/no-misused-promises': [
      'error',
      {
        checksVoidReturn: {
          arguments: false,
        },
      },
    ],
  },
  ignorePatterns: ['dist', 'node_modules', '*.js'],
};

