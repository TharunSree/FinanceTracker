import re

def extract_transaction_details(message: str):
    details = {
        "amount": None,
        "merchant": None,
        "transaction_type": None,  # debit/credit
        "upi_id": None,
        "bank_reference": None
    }

    # Extracting amount (e.g., 100, 100.50, etc.)
    amount_match = re.search(r'\b(\d+(?:\.\d{1,2})?)\b', message)
    if amount_match:
        details["amount"] = float(amount_match.group(1))

    # Extracting merchant or party involved (common patterns like "to", "from", "at")
    merchant_match = re.search(r'(to|from|at)\s+([A-Za-z0-9\s]+)', message, re.IGNORECASE)
    if merchant_match:
        details["merchant"] = merchant_match.group(2).strip()

    # Extracting transaction type (debit or credit)
    transaction_type_match = re.search(r'\b(debit|credit)\b', message, re.IGNORECASE)
    if transaction_type_match:
        details["transaction_type"] = transaction_type_match.group(1).lower()

    # Extracting UPI ID (pattern for UPI transactions: example@upi)
    upi_match = re.search(r'([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})', message)
    if upi_match:
        details["upi_id"] = upi_match.group(1)

    # Extracting bank reference number (if available)
    reference_match = re.search(r'\b([A-Za-z0-9]{10,})\b', message)
    if reference_match:
        details["bank_reference"] = reference_match.group(1)

    return details
