TOK=["nsfw","hentai","slut","pussy","nude","tits","milf","cuck","rule34","r34","hotwife","blowjob","creampie","yiff","titties","pawg","whore","twink","onlyfans","xxx","boobs","naked","upskirt","escort","topless","braless","futanari","lewd"]
ALLOW={"xxxtentacion","xxxtr","petitsbonheurs","kopiernudeln","milford","nonnude","sfw_rule34","anime_titties","nakedandafraid",
       "pussypassdenied","onlyfansadvice","onlyfansreviews","yiffinhell","wowthisnsfwsubexists","hornyjail","bigdickproblems","oddlyerotic",
       "churchoffutaba","classicalartnudes","milfhunterkakyoin","pansexmothwhorehouse",
       "askwomen"}
def rule(n):
    if n in ALLOW: return False
    if "sfw" in n.replace("nsfw",""): return False
    return any(t in n for t in TOK)
