module.exports = [
  { ignores: ["vendor/**", "coverage/**"] },
  {
    files: ["**/*.js"],
    languageOptions: {
      ecmaVersion: 2021,
      sourceType: "module",
    },
    rules: {
      "no-constant-condition": "error",
      "no-debugger": "error",
      "no-duplicate-case": "error",
      "no-unreachable": "error",
    },
  },
];
