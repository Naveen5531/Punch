/* ===== Company set-up for this copy of Punch =====
   To set Punch up for another company: change the values below, replace sn-logo.png (wide logo) and sn-mark.png
   (square icon) with that company's files, and host its portal (firebase-config.js, payslip.js, offer.js),
   Punch.apk and punch-version.json on its own website. Nothing else in the app needs to change. */
window.BRAND = {
  company: 'SN Logistics',                       // company name on letters, payslips, the login screen and updates
  tagline: 'Speed & Trust',
  site: 'https://naveen5531.github.io',          // website with Punch.apk and punch-version.json (in-app updates)
  portal: 'https://naveen5531.github.io/portal/', // staff portal: firebase-config.js, payslip.js, offer.js
  authDomain: 'staff.movana.app',                // login-name domain the portal uses (must match the portal)
  mailName: 'SN Mail',                           // name of the company mail in the app
  mailDomain: 'snlogistics',                     // company mail IDs look like name@snlogistics
  logo: 'sn-logo.png',
  mark: 'sn-mark.png',
  /* Grade levels: [letter, level name, example roles]. Grades are letter + 1..4 (e.g. S2). */
  grades: [
    ['F', 'Frontline', 'Associates, operators, drivers, loaders, support staff'],
    ['S', 'Supervisor', 'Team leader, senior associate, in-charge, assistant manager'],
    ['M', 'Manager', 'Department manager, branch / site manager, head-office manager'],
    ['G', 'General Manager', 'Regional head, function head, GM, AVP, VP']
  ]
};
