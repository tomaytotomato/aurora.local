import { http, HttpResponse } from 'msw';

// The dev-mode / test mock for the wipe-the-box endpoint (A8).
//
// A real reset takes down the container we are hitting; for MSW there is
// nothing to take down, so this just returns the accepted-shape response
// after enforcing the same confirmation policy as the backend so a UI bug
// that submits the wrong body still gets a 400 (and can be tested).
export const resetHandlers = [
  http.post('/api/reset', async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as { confirm?: string };
    if (body.confirm !== 'RESET') {
      return HttpResponse.json(
        { message: 'type RESET to confirm' },
        { status: 400 },
      );
    }
    return HttpResponse.json(
      { helperId: 'mock-reset-helper' },
      { status: 202 },
    );
  }),
];
