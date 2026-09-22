-- Rows written by the service before anyone was checking. Left here on purpose:
-- this is the state of production.
INSERT INTO ledger (account_last4, occurred_at, direction, amount, category, merchant, source_message_ids) VALUES
('4821','2026-06-28T11:04+05:30','DEBIT',  449.00,'SPEND','SWIGGY','m-legacy-0001'),
('4821','2026-06-28T11:04+05:30','DEBIT',  449.00,'SPEND','SWIGGY','m-legacy-0002'),
('4821','2026-06-28T11:04+05:30','DEBIT',  449.00,'SPEND','SWIGGY','m-legacy-0001'),
('9075','2026-06-28T19:41+05:30','DEBIT', 1299.50,'SPEND','MYNTRA','m-legacy-0007'),
('9075','2026-06-28T19:41+05:30','DEBIT', 1299.50,'SPEND','MYNTRA','m-legacy-0007'),
('4821','2026-06-29T09:15+05:30','CREDIT',45000.00,'INCOME','SALARY CREDIT','m-legacy-0011'),
('4821','2026-06-29T13:22+05:30','DEBIT',   30.00,'SPEND','UPI/CHAIWALA','m-legacy-0014'),
('4821','2026-06-29T13:22+05:30','DEBIT',   30.00,'SPEND','UPI/CHAIWALA','m-legacy-0015'),
('9075','2026-06-30T08:02+05:30','DEBIT',   20.00,'SPEND','UPI/MILK BOOTH','m-legacy-0019'),
('9075','2026-06-30T17:50+05:30','DEBIT', 5000.00,'SPEND','IMPS/P2A/PARAG KAPOOR','m-legacy-0023'),
('4821','2026-06-30T17:52+05:30','CREDIT',5000.00,'INCOME','IMPS/P2A/PARAG KAPOOR','m-legacy-0024'),
('4821','2026-06-30T21:10+05:30','DEBIT',  899.99,'SPEND','BIGBASKET','m-legacy-0028'),
('4821','2026-06-30T21:10+05:30','DEBIT',  899.99,'SPEND','BIGBASKET','m-legacy-0029'),
('9075','2026-06-30T22:45+05:30','DEBIT',   75.00,'SPEND','UPI/AUTO RICKSHAW','m-legacy-0033'),
('4821','2026-06-27T10:00+05:30','DEBIT',92213.10,'SPEND','UPI/WATER CAN','m-legacy-0041');
