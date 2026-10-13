from pathlib import Path
from reportlab.pdfgen import canvas
from pypdf import PdfReader

target = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/score.pdf"
target.parent.mkdir(parents=True, exist_ok=True)
pdf = canvas.Canvas(str(target), invariant=1, pageCompression=0)
for number in range(1, 5):
    height = 1200 if number == 3 else 900
    pdf.setPageSize((600, height))
    pdf.setFont("Helvetica", 28)
    pdf.drawString(60, height - 70, f"Ensemble - Page {number}")
    pdf.setLineWidth(1.5)
    for staff in range(8):
        top = height - 140 - staff * 90
        for line in range(5):
            pdf.line(50, top - line * 8, 550, top - line * 8)
        for note in range(8):
            x = 100 + note * 55
            y = top - ((note + number) % 5) * 8
            pdf.ellipse(x - 6, y - 4, x + 6, y + 4, stroke=0, fill=1)
            pdf.line(x + 6, y, x + 6, y + 27)
    pdf.showPage()
pdf.save()
reader = PdfReader(target)
assert len(reader.pages) == 4
assert float(reader.pages[2].mediabox.height) == 1200
print(f"Generated and verified 4-page vector PDF: {target}")
