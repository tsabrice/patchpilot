/** @type {import('tailwindcss').Config} */
module.exports = {
  // Tell Tailwind v3 exactly which files contain class names to generate CSS for.
  // Without this, Tailwind outputs only the base reset — no utility classes.
  content: [
    './src/**/*.{html,ts}',
  ],
  theme: {
    extend: {},
  },
  plugins: [],
};
