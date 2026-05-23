from PIL import Image

def fix_png(src, dest):
    print(f"Fixing {src} -> {dest}...")
    try:
        img = Image.open(src)
        if img.mode in ('RGBA', 'LA') or (img.mode == 'P' and 'transparency' in img.info):
            img = img.convert('RGBA')
        else:
            img = img.convert('RGB')
        img.save(dest, format='PNG', optimize=True)
        print("Success!")
    except Exception as e:
        print(f"Error: {e}")

fix_png(r"C:\Users\罗文\.gemini\antigravity\brain\2a121bc1-99be-466e-a857-6775db1c31b7\zen_knowledge_banner_1779510768538.png", r"e:\AIAssistant\app\src\main\res\drawable\zen_knowledge_banner.png")
fix_png(r"C:\Users\罗文\.gemini\antigravity\brain\2a121bc1-99be-466e-a857-6775db1c31b7\zen_pomo_banner_1779510792959.png", r"e:\AIAssistant\app\src\main\res\drawable\zen_pomo_banner.png")
