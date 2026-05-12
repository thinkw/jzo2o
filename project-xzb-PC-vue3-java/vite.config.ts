import { ConfigEnv, UserConfig, loadEnv } from 'vite'
import { viteMockServe } from 'vite-plugin-mock'
import createVuePlugin from '@vitejs/plugin-vue'
import vueJsx from '@vitejs/plugin-vue-jsx'
import svgLoader from 'vite-svg-loader'

import path from 'path'

const CWD = process.cwd()

// https://vitejs.dev/config/
export default ({ mode }: ConfigEnv): UserConfig => {
  const { VITE_BASE_URL } = loadEnv(mode, CWD)
  return {
    base: VITE_BASE_URL,
    define: {},
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
        // markstream-vue 未安装的可选依赖 → 空桩
        'stream-monaco': path.resolve(__dirname, './stubs/stream-monaco.js'),
        '@antv/infographic': path.resolve(__dirname, './stubs/@antv-infographic.js'),
        '@terrastruct/d2': path.resolve(__dirname, './stubs/@terrastruct-d2.js'),
      }
    },

    css: {
      preprocessorOptions: {
        less: {
          modifyVars: {
            hack: `true; @import (reference) "${path.resolve(
              'src/style/variables.less'
            )}";`
          },
          math: 'strict',
          javascriptEnabled: true
        }
      }
    },

    plugins: [
      createVuePlugin(),
      vueJsx(),
      viteMockServe({
        mockPath: 'mock',
        localEnabled: false,
        prodEnabled: true,
        supportTs: true,
        logger: true,
        injectCode: `
          import { setupProdMockServer } from '../mockProdServer';
          setupProdMockServer();
        `
      }),
      svgLoader()
    ],

    server: {
      port: 6001,
      host: '0.0.0.0',
      open: false,
      hmr: true,
      proxy: {
        '/api': {
          target: 'http://localhost:11500',
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api/, '')
        }
      }
    },

    build: {
      rollupOptions: {
        external: ['stream-monaco', '@antv/infographic', '@terrastruct/d2', 'vue-i18n']
      }
    },

    optimizeDeps: {
      exclude: ['stream-monaco', '@antv/infographic', '@terrastruct/d2']
    }
  }
}
