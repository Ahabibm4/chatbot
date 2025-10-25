import { defineConfig } from 'vitest/config';
import { resolve } from 'path';

export default defineConfig({
  esbuild: {
    loader: 'ts',
    include: [/src\/.*\.ts$/]
  },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/netcourier-chatbot.ts'),
      name: 'NetCourierChatbot',
      fileName: 'netcourier-chatbot',
      formats: ['umd']
    },
    rollupOptions: {
      external: [],
      output: {
        globals: {}
      }
    }
  },
  test: {
    environment: 'happy-dom',
    globals: true,
    deps: {
      /**
       * Enable the Vite Node loader so that TypeScript test files are
       * transpiled before being executed in the ESM-aware Vitest runtime.
       *
       * Without this flag Node would attempt to execute the raw `.ts` files
       * which still contain syntax such as `import type` and `satisfies`,
       * resulting in "Invalid or unexpected token" syntax errors when running
       * the suite.
       */
      registerNodeLoader: true
    },
    coverage: {
      reporter: ['text', 'json-summary']
    }
  }
});
