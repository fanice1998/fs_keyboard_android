#!/usr/bin/env python3
"""Move a review list between its TSV in git and an Excel file a reviewer edits.

Excel mangles a UTF-8 TSV, so review in .xlsx and bring the answers back:

    python -I tools/corpus/review_xlsx.py export data/review/c_testset.tsv
    (edit data/review/c_testset.xlsx in Excel, save, close)
    python -I tools/corpus/review_xlsx.py import data/review/c_testset.tsv

Import takes every cell from the workbook, so corrected readings come back too.
It refuses a workbook whose header row differs from the TSV. Needs `openpyxl`.
"""
import pathlib
import sys

import openpyxl
from openpyxl.styles import Alignment, Font, PatternFill


def read_tsv(path):
    with open(path, encoding="utf-8") as rows:
        return [line.rstrip("\n").split("\t") for line in rows]


def export(tsv):
    rows = read_tsv(tsv)
    book = openpyxl.Workbook()
    sheet = book.active
    sheet.title = tsv.stem
    for row in rows:
        sheet.append(row)
    sheet.freeze_panes = "A2"
    for cell in sheet[1]:
        cell.font = Font(bold=True)
    decision = len(rows[0])
    for (column,) in sheet.iter_cols(min_col=decision, max_col=decision, max_row=1):
        sheet.column_dimensions[column.column_letter].width = 14
    highlight = PatternFill("solid", fgColor="FFF2CC")
    for row in sheet.iter_rows(min_row=2, min_col=decision, max_col=decision):
        row[0].fill = highlight
    for index, header in enumerate(rows[0], 1):
        letter = sheet.cell(row=1, column=index).column_letter
        widest = max(len(row[index - 1]) for row in rows if len(row) >= index)
        sheet.column_dimensions[letter].width = min(max(widest * 2, 10), 60)
        if header in ("句子", "例句", "例句1", "例句2"):
            for (cell,) in sheet.iter_rows(min_row=2, min_col=index, max_col=index):
                cell.alignment = Alignment(wrap_text=True, vertical="top")
    out = tsv.with_suffix(".xlsx")
    book.save(out)
    print(f"wrote {out}")


def import_back(tsv):
    workbook = tsv.with_suffix(".xlsx")
    sheet = openpyxl.load_workbook(workbook, read_only=True).active
    rows = [["" if value is None else str(value) for value in row] for row in sheet.iter_rows(values_only=True)]
    header = read_tsv(tsv)[0]
    if not rows or rows[0][: len(header)] != header:
        sys.exit(f"{workbook} has header {rows[0] if rows else []}, expected {header}")
    with open(tsv, "w", encoding="utf-8", newline="\n") as out:
        for row in rows:
            cells = (row + [""] * len(header))[: len(header)]
            if not any(cells):
                continue
            out.write("\t".join(cell.replace("\t", " ").replace("\n", " ") for cell in cells) + "\n")
    decided = sum(1 for row in rows[1:] if len(row) >= len(header) and row[len(header) - 1])
    print(f"wrote {tsv}: {len(rows) - 1} rows, {decided} decided")


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("export", "import"):
        sys.exit(__doc__)
    tsv = pathlib.Path(sys.argv[2])
    export(tsv) if sys.argv[1] == "export" else import_back(tsv)


if __name__ == "__main__":
    main()
