import json
import os
from tokenizers import Tokenizer

def main():
    tokenizer_path = "model_deployment/tokenizer/tokenizer.json"
    if not os.path.exists(tokenizer_path):
        print(f"Error: Could not find {tokenizer_path}")
        return

    tokenizer = Tokenizer.from_file(tokenizer_path)

    test_cases = [
        "Fire",
        "Help",
        "Run",
        "Smoke",
        "FIRE IN BUILDING A",
        "Explosion!!! Chemical smell!!!",
        "Can't breathe",
        "Café brûlé",
        "🔥 Fire in the lab 🔥",
        "",
        "你好世界",
        " ",
        "\t\n\r",
        "pneumonoultramicroscopicsilicovolcanoconiosis",
        "a" * 105,  # word exceeding 100 chars
        "This is a long sentence that should exceed the maximum sequence length of 64 tokens and therefore demonstrate truncation correctly working right now ok let us keep typing until we get there please.",
        "Short sentence.",
        "?",
        "!?.",
        "𐍈", # Supplementary unicode character
    ]

    golden_vectors = []

    for text in test_cases:
        encoded = tokenizer.encode(text)
        
        # In case the tokenizer config does not automatically pad to 64, we pad it exactly to 64.
        # But tokenizer.json already has padding configuration to 64.
        # Let's verify lengths.
        input_ids = encoded.ids
        attention_mask = encoded.attention_mask

        # If lengths are not 64, manually adjust just in case, though the Tokenizer should handle it based on tokenizer.json
        if len(input_ids) > 64:
            input_ids = input_ids[:64]
            attention_mask = attention_mask[:64]
        
        while len(input_ids) < 64:
            input_ids.append(0)
            attention_mask.append(0)

        golden_vectors.append({
            "input": text,
            "input_ids": input_ids,
            "attention_mask": attention_mask
        })

    out_dir = "app/src/test/resources/emergency_ai"
    os.makedirs(out_dir, exist_ok=True)
    out_file = os.path.join(out_dir, "tokenizer_golden_vectors.json")
    
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(golden_vectors, f, indent=2, ensure_ascii=False)
    
    print(f"Generated {len(golden_vectors)} golden vectors to {out_file}")

if __name__ == "__main__":
    main()
