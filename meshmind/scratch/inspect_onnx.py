import onnx
import json
import os

model_path = r'D:\meshmind\meshmind\model_deployment\onnx\meshmind_classifier.onnx'
model = onnx.load(model_path, load_external_data=False)

print("ONNX Contract:")
print(f"IR Version: {model.ir_version}")
for opset in model.opset_import:
    print(f"Opset: {opset.domain} v{opset.version}")
print(f"Producer Name: {model.producer_name}")
print(f"Producer Version: {model.producer_version}")
print(f"Graph Name: {model.graph.name}")
print(f"Model Metadata Properties:")
for prop in model.metadata_props:
    print(f"  {prop.key}: {prop.value}")

print("External Data References:")
for initializer in model.graph.initializer:
    if initializer.HasField("data_location") and initializer.data_location == onnx.TensorProto.EXTERNAL:
        for entry in initializer.external_data:
            if entry.key == 'location':
                print(f"  External file referenced: {entry.value}")
                break
        break

print("Inputs:")
for input in model.graph.input:
    shape = []
    for dim in input.type.tensor_type.shape.dim:
        shape.append(dim.dim_value if dim.HasField("dim_value") else dim.dim_param)
    print(f"  {input.name}: type={input.type.tensor_type.elem_type}, shape={shape}")

print("Outputs:")
for output in model.graph.output:
    shape = []
    for dim in output.type.tensor_type.shape.dim:
        shape.append(dim.dim_value if dim.HasField("dim_value") else dim.dim_param)
    print(f"  {output.name}: type={output.type.tensor_type.elem_type}, shape={shape}")

print("Nodes at end of graph (check for Softmax):")
for node in model.graph.node[-5:]:
    print(f"  {node.op_type} - {node.output}")

print("\n--- TOKENIZER JSON ---")
with open(r'D:\meshmind\meshmind\model_deployment\tokenizer\tokenizer.json', 'r', encoding='utf-8') as f:
    tokenizer = json.load(f)

print(f"Model type: {tokenizer.get('model', {}).get('type')}")
print(f"Normalizer: {json.dumps(tokenizer.get('normalizer'), indent=2)}")
print(f"Pre-tokenizer: {json.dumps(tokenizer.get('pre_tokenizer'), indent=2)}")
print(f"Post-processor: {json.dumps(tokenizer.get('post_processor'), indent=2)}")
print(f"Truncation: {json.dumps(tokenizer.get('truncation'), indent=2)}")
print(f"Padding: {json.dumps(tokenizer.get('padding'), indent=2)}")
print(f"WordPiece config: unk={tokenizer.get('model', {}).get('unk_token')}, prefix={tokenizer.get('model', {}).get('continuing_subword_prefix')}, max_char={tokenizer.get('model', {}).get('max_input_chars_per_word')}")
print(f"Added tokens:")
for t in tokenizer.get('added_tokens', []):
    print(f"  {t.get('content')}: id={t.get('id')}")

print("\n--- CONFIG JSON ---")
with open(r'D:\meshmind\meshmind\model_deployment\tokenizer\config.json', 'r', encoding='utf-8') as f:
    config = json.load(f)

for k, v in config.items():
    if k in ['model_type', 'architectures', 'hidden_size', 'num_hidden_layers', 'num_attention_heads', 'intermediate_size', 'hidden_act', 'max_position_embeddings', 'vocab_size', 'type_vocab_size', 'pad_token_id']:
        print(f"{k}: {v}")
