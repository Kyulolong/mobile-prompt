// Install-time asset pack carrying the sherpa-onnx speech models. Install-time
// packs are mounted into the app's AssetManager, so the app reads them as
// plain assets ("models/ko/encoder.int8.onnx"). Files are not committed —
// run scripts/fetch-models.sh once after cloning.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("speechmodels")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
