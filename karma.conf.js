process.env.CHROME_BIN = require('puppeteer').executablePath();

module.exports = function (config) {
    config.set({
      browsers: ['ChromeHeadlessNoSandbox'],
      customLaunchers: {
        ChromeHeadlessNoSandbox: {
          base: 'ChromeHeadless',
          flags: [
            '--no-sandbox',
          ]
        }
      },
      // The directory where the output file lives
      basePath: 'test-assets/karma/',
      // The file itself
      files: [
        'ci.js',
      ],
      frameworks: ['cljs-test'],
      plugins: ['karma-cljs-test', 'karma-chrome-launcher'],
      colors: true,
      logLevel: config.LOG_INFO,
      client: {
        args: ["shadow.test.karma.init"],
        singleRun: true
      }
    })
};
