
MeshMind Deployment Package

Folder Structure

onnx/
    meshmind_classifier.onnx
    meshmind_classifier.onnx.data

tokenizer/
    HuggingFace tokenizer

pytorch/
    PyTorch checkpoint

metadata/
    configuration files

reports/
    evaluation plots

Deployment

Android:
    ONNX Runtime Mobile
    Qualcomm AI Hub

Inference Inputs

input_ids
attention_mask

Output

logits
