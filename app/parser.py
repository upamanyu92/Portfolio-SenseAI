from io import BytesIO

import openpyxl
import pypdf
from docx import Document


def extract_text(file_bytes: bytes, extension: str) -> str:
    extension = extension.lower()

    if extension == ".pdf":
        reader = pypdf.PdfReader(BytesIO(file_bytes))
        return " ".join((page.extract_text() or "") for page in reader.pages).strip()

    if extension == ".xlsx":
        workbook = openpyxl.load_workbook(BytesIO(file_bytes), data_only=True)
        sheet = workbook.active
        rows = []
        for row in sheet.iter_rows(values_only=True):
            rows.append(",".join("" if cell is None else str(cell) for cell in row))
        return "\n".join(rows).strip()

    if extension == ".docx":
        document = Document(BytesIO(file_bytes))
        return "\n".join(paragraph.text for paragraph in document.paragraphs).strip()

    return ""
