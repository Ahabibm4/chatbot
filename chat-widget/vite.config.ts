import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
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
    coverage: {
      reporter: ['text', 'json-summary']
    }
  }
});
