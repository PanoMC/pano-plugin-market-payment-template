// The note has no data of its own: it only shows the text of the gateway for the chosen method.
export const notApplicable = ['empty', 'loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { label: 'This gateway is the chosen method', props: { method: 'example' } },
};
