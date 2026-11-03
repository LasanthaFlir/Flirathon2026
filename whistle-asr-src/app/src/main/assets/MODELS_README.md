# Missing model files

This `assets/` folder is missing the 3 Moonshine v2 ONNX model files
(not committed to this repo — each is 50-130MB and exceeds GitHub's
100MB per-file limit):

- `moonshine_v2_encoder.onnx` (~54MB)
- `moonshine_v2_decoder_init.onnx` (~129MB)
- `moonshine_v2_decoder_step.onnx` (~123MB)

Copy them here from the original workspace (`~/QNN/QnnWhistleKotlin/app/src/main/assets/`)
before building, or regenerate them using the Moonshine v2 export/conversion
scripts from that workspace.
