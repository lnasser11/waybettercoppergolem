#!/usr/bin/env python3
"""Gera o guia do jogador (PDF) para o Way Better Copper Golem."""

import os

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (BaseDocTemplate, Frame, Image, KeepTogether, ListFlowable,
                                ListItem, NextPageTemplate, PageBreak, PageTemplate, Paragraph,
                                Spacer, Table, TableStyle)

COBRE = colors.HexColor("#B4642F")
COBRE_CLARO = colors.HexColor("#F3E4D6")
VERDIGRIS = colors.HexColor("#3E7D66")
VERDIGRIS_CLARO = colors.HexColor("#E4EFE9")
TINTA = colors.HexColor("#2B2320")
SUAVE = colors.HexColor("#6E6058")
LINHA = colors.HexColor("#E0D6C8")

PAGINA = A4
MARGEM = 18 * mm

estilos = getSampleStyleSheet()


def estilo(nome, **kw):
    base = kw.pop("parent", estilos["Normal"])
    return ParagraphStyle(nome, parent=base, **kw)


TITULO_CAPA = estilo("TituloCapa", fontName="Helvetica-Bold", fontSize=30, leading=34,
                     textColor=COBRE, alignment=TA_CENTER, spaceAfter=6)
SUB_CAPA = estilo("SubCapa", fontName="Helvetica", fontSize=13, leading=18,
                  textColor=SUAVE, alignment=TA_CENTER, spaceAfter=18)
H1 = estilo("H1", fontName="Helvetica-Bold", fontSize=17, leading=21, textColor=COBRE,
            spaceBefore=16, spaceAfter=8)
H2 = estilo("H2", fontName="Helvetica-Bold", fontSize=12.5, leading=16, textColor=TINTA,
            spaceBefore=11, spaceAfter=5)
CORPO = estilo("Corpo", fontName="Helvetica", fontSize=10, leading=14.5, textColor=TINTA,
               alignment=TA_JUSTIFY, spaceAfter=7)
CORPO_PEQ = estilo("CorpoPeq", parent=CORPO, fontSize=9, leading=13)
LEGENDA = estilo("Legenda", fontName="Helvetica-Oblique", fontSize=8.5, leading=12,
                 textColor=SUAVE, alignment=TA_CENTER, spaceBefore=4, spaceAfter=10)
MONO = estilo("Mono", fontName="Courier", fontSize=8.8, leading=12.5, textColor=TINTA)
ITEM = estilo("Item", parent=CORPO, spaceAfter=3, alignment=0)


def p(txt, s=CORPO):
    return Paragraph(txt, s)


def caixa(titulo, texto, cor_fundo, cor_borda):
    """Bloco destacado (dica, aviso, regra de ouro)."""
    interno = [Paragraph(f"<b>{titulo}</b>", estilo("CaixaTit", fontName="Helvetica-Bold",
                                                    fontSize=10, leading=13, textColor=cor_borda,
                                                    spaceAfter=3)),
               Paragraph(texto, estilo("CaixaTxt", parent=CORPO, spaceAfter=0, fontSize=9.5,
                                       leading=13.5))]
    t = Table([[interno]], colWidths=[PAGINA[0] - 2 * MARGEM])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), cor_fundo),
        ("LINEBEFORE", (0, 0), (0, -1), 2.2, cor_borda),
        ("LEFTPADDING", (0, 0), (-1, -1), 9),
        ("RIGHTPADDING", (0, 0), (-1, -1), 9),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    return KeepTogether([t, Spacer(1, 9)])


def dica(texto):
    return caixa("Dica", texto, VERDIGRIS_CLARO, VERDIGRIS)


def atencao(texto):
    return caixa("Atenção", texto, COBRE_CLARO, COBRE)


def tabela(dados, larguras, cabecalho=True):
    t = Table(dados, colWidths=larguras, repeatRows=1 if cabecalho else 0)
    estilo_t = [
        ("FONTNAME", (0, 0), (-1, -1), "Helvetica"),
        ("FONTSIZE", (0, 0), (-1, -1), 9),
        ("TEXTCOLOR", (0, 0), (-1, -1), TINTA),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LINEBELOW", (0, 0), (-1, -2), 0.4, LINHA),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
    ]
    if cabecalho:
        estilo_t += [
            ("BACKGROUND", (0, 0), (-1, 0), COBRE),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ]
    t.setStyle(TableStyle(estilo_t))
    return t


def lista(itens, numerada=False):
    return ListFlowable([ListItem(p(i, ITEM), leftIndent=14) for i in itens],
                        bulletType="1" if numerada else "bullet",
                        bulletColor=COBRE, bulletFontSize=8, leftIndent=16,
                        start="1" if numerada else None)


def codigo(linhas):
    txt = "<br/>".join(linhas)
    t = Table([[Paragraph(txt, MONO)]], colWidths=[PAGINA[0] - 2 * MARGEM])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#F5F1EA")),
        ("BOX", (0, 0), (-1, -1), 0.5, LINHA),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    return KeepTogether([t, Spacer(1, 9)])


def imagem(caminho, largura_mm, legenda):
    largura = largura_mm * mm
    from reportlab.lib.utils import ImageReader
    iw, ih = ImageReader(caminho).getSize()
    altura = largura * ih / iw
    img = Image(caminho, width=largura, height=altura)
    img.hAlign = "CENTER"
    return KeepTogether([img, p(legenda, LEGENDA)])


def rodape(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(LINHA)
    canvas.setLineWidth(0.5)
    canvas.line(MARGEM, 14 * mm, PAGINA[0] - MARGEM, 14 * mm)
    canvas.setFont("Helvetica", 7.5)
    canvas.setFillColor(SUAVE)
    canvas.drawString(MARGEM, 9.5 * mm, "Way Better Copper Golem — Guia do Jogador")
    canvas.drawRightString(PAGINA[0] - MARGEM, 9.5 * mm, f"pág. {doc.page}")
    canvas.restoreState()


def capa(canvas, doc):
    canvas.saveState()
    canvas.setFillColor(COBRE)
    canvas.rect(0, PAGINA[1] - 12 * mm, PAGINA[0], 12 * mm, stroke=0, fill=1)
    canvas.setFillColor(VERDIGRIS)
    canvas.rect(0, 0, PAGINA[0], 6 * mm, stroke=0, fill=1)
    canvas.restoreState()


SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "img")


def construir(saida):
    doc = BaseDocTemplate(saida, pagesize=PAGINA,
                          leftMargin=MARGEM, rightMargin=MARGEM,
                          topMargin=20 * mm, bottomMargin=20 * mm,
                          title="Way Better Copper Golem — Guia do Jogador",
                          author="lnasser11", subject="Como usar o mod de organização dos golens de cobre")
    quadro = Frame(MARGEM, 20 * mm, PAGINA[0] - 2 * MARGEM, PAGINA[1] - 40 * mm, id="corpo")
    doc.addPageTemplates([
        PageTemplate(id="capa", frames=[quadro], onPage=capa),
        PageTemplate(id="normal", frames=[quadro], onPage=rodape),
    ])

    s = []

    # ---------------- Capa ----------------
    s.append(Spacer(1, 30 * mm))
    s.append(p("Way Better Copper Golem", TITULO_CAPA))
    s.append(p("Guia do Jogador — como usar (e dominar)<br/>os golens de cobre do servidor", SUB_CAPA))
    s.append(Spacer(1, 6 * mm))
    s.append(caixa("Em uma frase",
                   "Você pendura um <b>quadro com um item</b> num baú para dizer o que vai ali dentro. "
                   "Os golens de cobre leem esses rótulos e passam a guardar tudo no lugar certo — "
                   "e ainda arrumam sozinhos o que estiver fora de lugar.",
                   COBRE_CLARO, COBRE))
    s.append(Spacer(1, 4 * mm))
    s.append(p("Este guia cobre desde o primeiro baú rotulado até os truques que fazem "
               "uma sala de estoque inteira se organizar sozinha. Não precisa saber nada de "
               "comandos ou mods para começar: as duas primeiras páginas já bastam para usar "
               "o mod bem.", CORPO))
    s.append(Spacer(1, 8 * mm))
    s.append(tabela([
        ["Versão do jogo", "Minecraft Java 26.2 (Fabric)"],
        ["Onde instalar", "No servidor e em TODOS os clientes"],
        ["Precisa de comandos?", "Não — tudo funciona com quadros e cliques"],
    ], [45 * mm, None], cabecalho=False))
    s.append(NextPageTemplate("normal"))
    s.append(PageBreak())

    # ---------------- Índice / o que muda ----------------
    s.append(p("O que muda em relação ao jogo normal", H1))
    s.append(p("No Minecraft vanilla, o golem de cobre pega até 16 itens de um baú de cobre e "
               "sai procurando um baú comum para largar. Ele só aceita um baú que esteja "
               "<b>vazio</b> ou que <b>já tenha aquele mesmo item</b>. Ou seja: ele só reforça a "
               "bagunça que já existe, e o primeiro item que cair num baú vazio decide para "
               "sempre o que aquele baú é.", CORPO))
    s.append(p("Com o mod, <b>você</b> decide o que vai em cada baú, pendurando um quadro com um "
               "item de exemplo. O golem passa a entregar cada coisa no baú certo, escolhendo "
               "sempre o rótulo mais específico que combina com o item.", CORPO))

    s.append(p("O que os golens ganham", H2))
    s.append(lista([
        "<b>Rótulos de verdade:</b> um baú marcado como “Redstone” só aceita redstone.",
        "<b>Do específico para o geral:</b> lingote de ferro vai para o baú de <i>ferro</i> antes do baú de <i>lingotes</i>; se o específico encher, ele desce para o mais genérico sozinho.",
        "<b>Arrumação automática:</b> quando não há nada para distribuir, eles recolhem itens que estão no baú errado e levam para o certo.",
        "<b>Alcance vertical:</b> conseguem usar paredes de baús de 4 ou 5 de altura sem subir em nada.",
        "<b>Baús proibidos:</b> um quadro com teia de aranha marca um baú que golem nenhum encosta.",
        "<b>Modo simulação:</b> dá para ver no log tudo o que eles <i>fariam</i>, sem mover um item sequer.",
    ]))

    s.append(p("Instalação", H1))
    s.append(p("O mod precisa estar instalado <b>no servidor e em todos os clientes</b>. Não é "
               "opcional: quem entrar sem o mod é desconectado na hora de logar.", CORPO))
    s.append(lista([
        "Instale o <b>Fabric Loader</b> para a versão <b>26.2</b> (fabricmc.net/use).",
        "Baixe o <b>Fabric API</b> para 26.2.",
        "Coloque o <b>Fabric API</b> e o <b>waybettercoppergolem-1.0.0.jar</b> na pasta <font face='Courier'>mods</font>.",
        "Abra o jogo pelo perfil do Fabric e entre no servidor normalmente.",
    ], numerada=True))
    s.append(dica("Todos precisam usar exatamente o <b>mesmo arquivo .jar</b> do mod. Versões "
                  "diferentes entre jogadores causam erro de conexão."))
    s.append(PageBreak())

    # ---------------- Começo rápido ----------------
    s.append(p("Começo rápido: seu primeiro baú organizado", H1))
    s.append(p("Cinco passos, dois minutos. Faça isso uma vez e o resto do guia fica óbvio.", CORPO))
    s.append(lista([
        "Coloque um <b>baú de cobre</b> perto da sua sala de estoque. É nele que você joga o "
        "material bruto — é a “caixa de entrada” dos golens.",
        "Coloque um <b>baú comum</b> a alguns blocos de distância.",
        "Pendure um <b>quadro (item frame)</b> na frente desse baú comum e coloque dentro dele "
        "um <b>lingote de ferro</b>.",
        "Dê <b>shift + clique direito</b> no quadro, com a <b>mão vazia</b>. Vai aparecer uma "
        "mensagem na parte de baixo da tela dizendo o que o baú passou a significar. Continue "
        "clicando para trocar entre: <i>só lingote de ferro</i> → <i>qualquer lingote de ferro</i> "
        "→ <i>qualquer lingote</i> → <i>categoria Minérios e Minerais</i>.",
        "Jogue lingotes de ferro no <b>baú de cobre</b> e espere. O golem vai buscar e entregar "
        "no baú que você acabou de rotular.",
    ], numerada=True))
    s.append(dica("Os golens trabalham devagar de propósito: eles ficam cerca de 3 segundos em "
                  "cada baú e dão uma pausa quando não acham nada. Se parecer parado, espere "
                  "meio minuto antes de achar que quebrou."))

    s.append(p("Rotulando baús: tudo o que um quadro pode dizer", H1))
    s.append(p("O quadro precisa estar <b>preso no próprio baú</b> (qualquer face, inclusive "
               "em cima). O item dentro dele é só um exemplo — quem manda é o rótulo que você "
               "escolhe com shift + clique.", CORPO))
    s.append(tabela([
        ["O que você põe no quadro", "O que o baú passa a ser"],
        ["Um item qualquer\n(e shift-clique para escolher o nível)",
         "Baú daquele item, daquela tag ou daquela categoria — do mais específico ao mais geral"],
        ["Quadro vazio", "Baú <b>pega-tudo</b>: recebe tudo que não combina com nenhum outro rótulo"],
        ["Teia de aranha", "Baú <b>proibido</b>: nenhum golem coloca, tira ou reorganiza nada ali"],
        ["Vários quadros no mesmo baú", "O baú aceita todas essas categorias ao mesmo tempo"],
        ["Nenhum quadro", "Comportamento vanilla — o mod não mexe nesse baú"],
    ], [58 * mm, None]))
    s.append(Spacer(1, 3 * mm))
    s.append(dica("Em <b>baú duplo</b>, um quadro em qualquer uma das metades vale para o baú inteiro."))
    s.append(PageBreak())

    # ---------------- Detalhes de rótulo ----------------
    s.append(p("Detalhes que fazem diferença", H1))

    s.append(p("Trocar o ícone sem perder o rótulo", H2))
    s.append(p("Depois que um quadro está numa categoria, o item mostrado vira <b>só decoração</b>. "
               "Você pode tirar o pistão e colocar uma tocha de redstone (ou um diamante, ou o que "
               "ficar bonito na parede) que o baú continua sendo o baú de Redstone. A única exceção "
               "é a <b>teia de aranha</b>, que sempre significa “proibido”.", CORPO))
    s.append(atencao("Se você der shift + clique de novo num quadro cujo ícone foi trocado, o ciclo "
                     "recomeça a partir do item que está lá agora. Trocou o ícone? Não fique "
                     "shift-clicando nele."))

    s.append(p("O baú lembra do rótulo", H2))
    s.append(p("Se um creeper explodir o quadro, o baú <b>não</b> perde a categoria — ele continua "
               "funcionando até você pendurar um quadro novo. Isso evita que a sala inteira se "
               "embaralhe por causa de um acidente.", CORPO))
    s.append(p("O efeito colateral é que um baú também continua rotulado depois que você tira o "
               "quadro de propósito. Para realmente “desrotular”, olhe para o baú e use:", CORPO))
    s.append(codigo(["/wbcg chest info    → diz o rótulo atual e se veio do quadro ou da memória",
                     "/wbcg chest clear   → faz o baú voltar a ser um baú comum sem rótulo"]))

    s.append(p("Como o golem escolhe o baú", H1))
    s.append(p("Quando um golem está com um item na mão, ele procura destino nesta ordem:", CORPO))
    s.append(lista([
        "Um baú rotulado com <b>aquele item exato</b>.",
        "Um baú rotulado com uma <b>tag ou categoria que contém o item</b> — da mais específica "
        "para a mais genérica.",
        "O baú <b>pega-tudo</b> (quadro vazio).",
        "Um baú <b>sem rótulo</b>, seguindo a regra vanilla (vazio ou que já tem aquele item).",
    ], numerada=True))
    s.append(p("Além disso:", CORPO))
    s.append(lista([
        "<b>Baú cheio desce de nível:</b> se o baú de ferro encheu, o ferro vai para o baú de lingotes.",
        "<b>Baús gêmeos não se dividem:</b> entre dois baús com o mesmo rótulo, o golem prefere o "
        "que já tem aquele item, para não espalhar a mesma coisa em dois lugares.",
        "<b>Nada é largado no lugar errado:</b> se nenhum baú aceita o item, o golem devolve para "
        "o baú de cobre e tenta de novo mais tarde, em vez de ficar parado segurando.",
        "<b>Um item perdido não redefine nada:</b> uma stack de terra no baú de minérios não "
        "transforma aquele baú em baú de terra — quem manda é o rótulo.",
    ]))
    s.append(PageBreak())

    # ---------------- Categorias ----------------
    s.append(p("Categorias", H1))
    s.append(p("Tags do jogo são precisas (“lingotes”, “troncos”) mas não cobrem as categorias que a "
               "gente usa de verdade numa sala de estoque. Por isso o mod já vem com <b>12 categorias "
               "prontas</b>, que aparecem como as últimas opções do ciclo do quadro:", CORPO))
    s.append(tabela([
        ["Blocos de Construção", "Madeira", "Pedra e Terra"],
        ["Redstone", "Comida", "Agricultura"],
        ["Minérios e Minerais", "Ferramentas e Equipamentos", "Combate"],
        ["Drops de Mobs", "Nether e End", "Decoração"],
    ], [None, None, None], cabecalho=False))
    s.append(Spacer(1, 4 * mm))

    s.append(p("Ajustando uma categoria (o jeito rápido)", H2))
    s.append(p("Nenhuma categoria pronta vai combinar exatamente com a sua base. Para corrigir: "
               "<b>segure o item na mão e dê shift + clique direito no quadro da categoria</b>. "
               "Uma mensagem confirma o que aconteceu, por exemplo "
               "<i>“Pedra Luminosa adicionado a Redstone”</i>. Clicando de novo com o mesmo item, "
               "ele sai da categoria.", CORPO))
    s.append(dica("O ajuste vale para o <b>servidor inteiro</b>: todos os baús rotulados com aquela "
                  "categoria passam a seguir a nova regra, e isso fica salvo no mundo."))

    s.append(p("O Editor de Categorias", H2))
    s.append(p("Para mexer em várias coisas de uma vez, use o editor: agache e clique com o botão "
               "direito num <b>baú de cobre</b> com a mão vazia e clique em <b>Categorias…</b>.", CORPO))
    s.append(imagem(f"{SHOTS}/editor_categorias.png", 150,
                    "O Editor de Categorias. À esquerda, as seções; no meio, os itens; embaixo, o item sob o cursor. "
                    "(imagem de um cliente em inglês — no seu jogo os textos aparecem em português)"))
    s.append(lista([
        "<b>Botão de cima:</b> escolhe qual categoria você está editando (abre a lista).",
        "<b>Na categoria (N):</b> é a primeira seção da lista e mostra <b>o que já está dentro</b> "
        "daquela categoria. É aqui que você tira o que não deveria estar lá.",
        "<b>Seções:</b> as mesmas abas do inventário criativo, com a quantidade de itens. Você vê "
        "uma seção por vez, em vez de rolar o jogo inteiro.",
        "<b>Busca:</b> procura em <b>todos</b> os itens, ignorando a seção escolhida. É o jeito mais "
        "rápido: digite “glow”, clique no item.",
        "<b>Clique num item</b> para colocar ou tirar da categoria. Marcas no canto do quadradinho "
        "mostram o que você mexeu.",
    ]))
    s.append(PageBreak())

    s.append(p("Criando suas próprias categorias", H2))
    s.append(p("Os 12 presets não te prendem. No editor, o botão <b>+</b> cria uma categoria nova "
               "com o nome que você quiser — “Loot do Oceano”, “Trocas de Villager”, “Coisas da "
               "Fazenda”. Ela começa vazia; você clica os itens para dentro dela. O botão <b>x</b> "
               "apaga uma categoria sua (as 12 prontas não podem ser apagadas, só ajustadas).", CORPO))
    s.append(p("Depois de criada e preenchida, ela vira uma opção do ciclo do quadro em qualquer "
               "item que esteja dentro dela — ou seja, você rotula um baú com ela igualzinho às "
               "categorias prontas.", CORPO))
    s.append(codigo(["/wbcg category create Loot do Oceano     (também dá para criar por comando)",
                     "/wbcg category delete loot_do_oceano"]))

    # ---------------- Configurações ----------------
    s.append(p("Configurações do baú de cobre", H1))
    s.append(p("Cada baú de cobre é uma <b>zona de organização</b>. Agache e clique com o botão "
               "direito nele com a <b>mão vazia</b> para abrir os ajustes. Eles valem para os "
               "golens que trabalham naquele baú — dá para ter uma zona configurada de um jeito na "
               "sua base e outra diferente na base do amigo.", CORPO))
    s.append(imagem(f"{SHOTS}/configuracoes_zona.png", 128,
                    "A tela de configurações da zona. (imagem de um cliente em inglês)"))
    s.append(tabela([
        ["Ajuste", "Padrão", "O que faz"],
        ["Modo vanilla", "Desligado", "Liga/desliga o mod inteiro <b>nessa zona</b>. Ligado, os golens "
                                      "agem como no jogo normal e ignoram todos os outros ajustes "
                                      "(eles ficam cinzas). Seus rótulos continuam salvos."],
        ["Reorganizar baús", "Ligado", "Deixa os golens recolherem itens que estão no baú errado"],
        ["Arrumar dentro dos baús", "Desligado", "Junta stacks pela metade e tira os buracos dos baús que o golem visita"],
        ["Simulação (dry run)", "Desligado", "Só escreve no log o que ele faria, sem mover nada"],
        ["Raio de busca", "32", "Distância horizontal que ele procura baús (4 a 48)"],
        ["Alcance vertical", "4", "Altura que ele alcança numa parede de baús (1 a 6)"],
        ["Quantidade carregada", "16", "Itens por viagem: 16 é o ritmo vanilla, até 64 para ir mais rápido"],
    ], [40 * mm, 22 * mm, None]))
    s.append(PageBreak())

    # ---------------- Simulação e dicas ----------------
    s.append(p("Modo simulação: teste sem risco", H1))
    s.append(p("Antes de soltar os golens numa sala de estoque de verdade, ligue a <b>Simulação</b>. "
               "Eles vão até os baús, abrem, e <b>não movem nada</b> — só escrevem no log do "
               "servidor o que teriam feito:", CORPO))
    s.append(codigo(["[DRY-RUN] would move 12x minecraft:iron_ingot",
                     "          from minecraft:copper_chest@0,-59,0",
                     "          to   minecraft:chest@8,-59,0"]))
    s.append(p("Se as linhas apontarem para os baús certos, desligue a simulação e deixe trabalhar. "
               "É a forma segura de conferir se seus rótulos estão como você imaginou.", CORPO))

    s.append(p("Receita de uma sala de estoque que se cuida sozinha", H1))
    s.append(lista([
        "<b>Um baú de cobre bem na entrada.</b> É onde você despeja tudo ao voltar de uma "
        "expedição. Pode ter mais de um se a sala for grande.",
        "<b>Sempre tenha um baú pega-tudo</b> (quadro vazio). Sem ele, itens que não combinam com "
        "nada ficam voltando para o baú de cobre.",
        "<b>Do geral para o específico:</b> comece com categorias largas (Blocos de Construção, "
        "Madeira, Comida) e só crie baús específicos quando algo encher demais — o mod desce "
        "sozinho para o baú genérico quando o específico lota.",
        "<b>Paredes de baús funcionam.</b> Com alcance 4, o golem serve uma parede de 4–5 baús de "
        "altura do chão. Aproveite a verticalidade em vez de espalhar pelo chão.",
        "<b>Proteja o que é seu:</b> teia de aranha no quadro do baú de combustível do forno, do "
        "baú pessoal, ou de qualquer coisa que golem nenhum deva encostar.",
        "<b>Deixe reorganizar ligado.</b> É o que faz a sala se limpar sozinha depois que alguém "
        "guarda algo com pressa no lugar errado.",
    ]))
    s.append(PageBreak())

    # ---------------- Comandos e FAQ ----------------
    s.append(p("Comandos", H1))
    s.append(p("Você não precisa de nenhum deles para usar o mod, mas eles ajudam a conferir "
               "as coisas. Os marcados com (op) só funcionam para operadores.", CORPO))
    s.append(tabela([
        ["Comando", "O que faz"],
        ["/wbcg chest info", "Diz o rótulo do baú que você está olhando"],
        ["/wbcg chest clear", "Apaga o rótulo lembrado de um baú sem quadro"],
        ["/wbcg categories", "Lista todas as categorias e seus tamanhos"],
        ["/wbcg category list &lt;nome&gt;", "Mostra o que foi adicionado/removido de uma categoria"],
        ["/wbcg category test &lt;nome&gt; &lt;item&gt;", "Responde se um item está numa categoria"],
        ["/wbcg category create &lt;nome&gt;", "Cria uma categoria sua"],
        ["/wbcg category delete &lt;nome&gt;", "Apaga uma categoria sua"],
        ["/wbcg category add|remove … (op)", "Coloca/tira um item de uma categoria por comando"],
        ["/wbcg category reset &lt;nome&gt; (op)", "Desfaz todos os ajustes de uma categoria"],
    ], [66 * mm, None]))

    s.append(p("Problemas comuns", H1))
    s.append(tabela([
        ["Situação", "O que está acontecendo"],
        ["O golem está parado sem fazer nada",
         "Normal: ele pausa quando não há nada para distribuir. A arrumação automática também é "
         "lenta de propósito. Confira se o baú de cobre tem itens e se você está perto (chunks carregados)."],
        ["Ele guardou no baú errado",
         "Provavelmente o baú de destino não tem rótulo e estava vazio — nesse caso vale a regra "
         "vanilla. Rotule o baú, ou use <font face='Courier'>/wbcg chest info</font> para ver o que ele acha que é."],
        ["Rotulei o baú mas ele continua ignorando",
         "O quadro precisa estar preso <b>no próprio baú</b>. Um quadro na parede atrás do baú não conta."],
        ["Tirei o quadro e o baú continua rotulado",
         "É de propósito (proteção contra creeper). Use <font face='Courier'>/wbcg chest clear</font> olhando para o baú."],
        ["Um item que eu quero na categoria não vai",
         "Segure o item e dê shift-clique no quadro da categoria para adicioná-lo, ou use o Editor de Categorias."],
        ["Quero que os golens parem de mexer num baú",
         "Coloque um quadro com <b>teia de aranha</b> nele."],
        ["Quero tudo como no jogo normal de novo",
         "Ligue o <b>Modo vanilla</b> na tela do baú de cobre. Nada é perdido — é só desligar para voltar."],
    ], [55 * mm, None]))

    s.append(Spacer(1, 5 * mm))
    s.append(caixa("As três regras de ouro",
                   "<b>1.</b> Quadro preso no baú = rótulo. Shift + clique com a mão vazia escolhe o nível.<br/>"
                   "<b>2.</b> Sempre tenha um baú pega-tudo (quadro vazio) e proteja o que é seu com teia de aranha.<br/>"
                   "<b>3.</b> Na dúvida, ligue a Simulação e veja no log o que os golens fariam antes de deixá-los agir.",
                   VERDIGRIS_CLARO, VERDIGRIS))

    doc.build(s)


if __name__ == "__main__":
    import sys
    construir(sys.argv[1] if len(sys.argv) > 1 else "guia.pdf")
    print("PDF gerado")
