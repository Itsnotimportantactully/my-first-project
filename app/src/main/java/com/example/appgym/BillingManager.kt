package com.example.appgym

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*

class BillingManager(
    context: Context,
    private val listener: (String) -> Unit
) : PurchasesUpdatedListener {

    private val client: BillingClient = BillingClient.newBuilder(context)
        .enablePendingPurchases()
        .setListener(this)
        .build()

    fun connect(onReady: () -> Unit = {}) {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {}
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) onReady()
            }
        })
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        listener("Billing update: code=${result.responseCode} purchases=${purchases?.size ?: 0}")
    }

    fun launchSubPurchase(activity: Activity, productId: String) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        client.queryProductDetailsAsync(params) { br, list ->
            if (br.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val pd = list.firstOrNull() ?: return@queryProductDetailsAsync
            val offerToken = pd.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return@queryProductDetailsAsync

            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(pd)
                            .setOfferToken(offerToken)
                            .build()
                    )
                )
                .build()

            client.launchBillingFlow(activity, flow)
        }
    }
}
