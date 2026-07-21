from pathlib import Path
from openpyxl import Workbook


def main() -> None:
    out_path = Path("C:/AI/InfluencerCRM/tests/sample_brand_owner_creator_import.xlsx")

    # Brand-owner friendly column names (different from internal model fields)
    headers = [
        "Campaign Title",
        "Creator Display Name",
        "IG Handle",
        "Primary Channel",
        "Workstream Stage",
        "Estimated Creator Fee (USD)",
        "Audience Size",
        "Avg Engagement %",
        "Contact Email",
        "Region",
        "Content Pillars",
        "Preferred Publish Date",
        "Creator Notes",
    ]

    # Rows mimic creator + campaign_creator import intent with varied naming conventions
    rows = [
        [
            "Back to School Capsule",
            "Sam Rivera",
            "@samstyles",
            "instagram",
            "outreach",
            1800,
            125000,
            4.6,
            "sam.rivera@example.com",
            "US",
            "fashion,lifestyle",
            "2026-08-05",
            "Strong short-form conversion history.",
        ],
        [
            "Back to School Capsule",
            "Mina Cho",
            "@minamakes",
            "instagram",
            "contacted",
            2400,
            210000,
            3.9,
            "mina.cho@example.com",
            "US",
            "beauty,skincare",
            "2026-08-07",
            "Best for tutorial-style sponsored posts.",
        ],
        [
            "Holiday Glow Launch",
            "Tariq Owens",
            "@tariqtravels",
            "instagram",
            "negotiation",
            3200,
            305000,
            5.1,
            "tariq.owens@example.com",
            "CA",
            "travel,lifestyle",
            "2026-11-03",
            "Audience skews 25-34; high story completion.",
        ],
        [
            "Holiday Glow Launch",
            "Nadia Park",
            "@nadiaparkdaily",
            "instagram",
            "contract_sent",
            4100,
            460000,
            4.2,
            "nadia.park@example.com",
            "UK",
            "wellness,fitness",
            "2026-11-06",
            "Good candidate for whitelisting/usage rights.",
        ],
        [
            "Spring Refresh Drops",
            "Leo Martinez",
            "@leolooks",
            "instagram",
            "booked",
            2750,
            188000,
            4.8,
            "leo.martinez@example.com",
            "US",
            "menswear,grooming",
            "2027-03-14",
            "Fast turnaround; reliable revision cycle.",
        ],
    ]

    wb = Workbook()
    ws = wb.active
    ws.title = "Creator Import"

    ws.append(headers)
    for row in rows:
        ws.append(row)

    # Keep widths readable for manual review during testing
    widths = {
        "A": 28,
        "B": 24,
        "C": 18,
        "D": 18,
        "E": 20,
        "F": 28,
        "G": 16,
        "H": 18,
        "I": 30,
        "J": 12,
        "K": 26,
        "L": 24,
        "M": 44,
    }
    for col, width in widths.items():
        ws.column_dimensions[col].width = width

    wb.save(out_path)
    print(f"WROTE: {out_path}")


if __name__ == "__main__":
    main()
