import json
import os
import re
import unicodedata
from tokenizers import Tokenizer

URL_RE = re.compile(r"https?://\S+|www\.\S+")
USER_RE = re.compile(r"@\w+")
HTML_ENTITY_RE = re.compile(r"&[a-zA-Z]+;|&#\d+;")
WS_RE = re.compile(r"\s+")

def clean_text(t):
    t = unicodedata.normalize("NFKC", str(t))
    t = URL_RE.sub(" ", t)
    t = HTML_ENTITY_RE.sub(" ", t)
    t = USER_RE.sub(" ", t)
    t = re.sub(r"[^\x00-\x7F]+", " ", t)
    t = re.sub(r"#(\w+)", r"\1", t)
    t = re.sub(r"\brt\b", " ", t, flags=re.IGNORECASE)
    t = WS_RE.sub(" ", t).strip()
    return t

def main():
    tokenizer_path = "model_deployment/tokenizer/tokenizer.json"
    tokenizer = Tokenizer.from_file(tokenizer_path) if os.path.exists(tokenizer_path) else None

    test_cases = [
        "plain emergency text",
        "UPPERCASE TEXT",
        "URL beginning with http://example.com",
        "URL beginning with https://example.com",
        "URL beginning with www.example.com",
        "Multiple URLs http://example.com and www.test.com",
        "HTML named entities fire &amp; smoke",
        "HTML numeric entities help &#123; ambulance",
        "username @user",
        "multiple usernames @user1 and @user2",
        "emoji 🔥 FIRE 🔥",
        "CJK characters 火災 fire",
        "accented characters café fire",
        "NFKC compatibility characters ＦＩＲＥ in building",
        "full-width Latin characters ｈｅｌｐ",
        "hashtag #fire",
        "multiple hashtags #BuildingCollapse #emergency",
        "isolated lowercase rt",
        "isolated uppercase RT",
        "mixed-case Rt",
        "rt embedded in another word smart",
        "multiple spaces  between   words",
        "tabs\tbetween\twords",
        "newlines\nbetween\nwords",
        " leading whitespace",
        "trailing whitespace ",
        "negation words not fire no smoke",
        "operational keywords ambulance rescue evacuation",
        "combined www.example.com #flood @user",
        "combined mojibake/non-ASCII + RT 火災 RT fire",
        "FIRE IN BUILDING A",
        "@rescue Fire near block 2",
        "RT #fire reported at https://example.com",
        "No fire in building",
        "cannot evacuate trapped people",
        "Smoke &amp; fire reported",
        "#BuildingCollapse near gate",
        "RT RT fire",
        "rtfire",
        "without ambulance",
        "",
        "   ",
        "🔥"
    ]

    golden_vectors = []

    for i, text in enumerate(test_cases):
        cleaned = clean_text(text)
        
        vector = {
            "id": i,
            "input": text,
            "expectedCleanText": cleaned
        }
        
        if tokenizer:
            encoded = tokenizer.encode(cleaned)
            input_ids = encoded.ids
            attention_mask = encoded.attention_mask
            
            if len(input_ids) > 64:
                input_ids = input_ids[:64]
                attention_mask = attention_mask[:64]
                
            while len(input_ids) < 64:
                input_ids.append(0)
                attention_mask.append(0)
                
            vector["expectedInputIds"] = input_ids
            vector["expectedAttentionMask"] = attention_mask
            
        golden_vectors.append(vector)

    out_dir = "app/src/test/resources/emergency_ai"
    os.makedirs(out_dir, exist_ok=True)
    out_file = os.path.join(out_dir, "preprocessing_golden_vectors.json")
    
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(golden_vectors, f, indent=2, ensure_ascii=False)
    
    print(f"Generated {len(golden_vectors)} golden vectors to {out_file}")

if __name__ == "__main__":
    main()
