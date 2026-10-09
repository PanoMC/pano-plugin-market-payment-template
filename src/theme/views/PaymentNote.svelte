<div class="market-example-payment-note" data-method={method}>
  <p class="market-example-payment-note__text mb-0 text-body-secondary">{$_('descriptor.hint')}</p>
</div>

<script module>
  // A slot view: `view.slot` puts it into the market's checkout, under the payment method list. The market renders the
  // slot with { quote, method, onselect } and keeps the item whose `id` is the chosen method's id (this plugin's
  // provider id). An in-page step of the order page uses the slot market:order:payment (id = the gateway key) instead.
  export const view = { slot: 'market:checkout:payment', id: "example" };
</script>

<script>
  import { plugin } from '@panomc/sdk/controllers';

  const { _ } = plugin('market-example');

  /** id of the chosen payment method (market props of the slot: quote, method, onselect). */
  let { method = '' } = $props();
</script>
